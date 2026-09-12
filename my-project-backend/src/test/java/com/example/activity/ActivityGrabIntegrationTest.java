package com.example.activity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.example.config.RabbitConfiguration;
import com.example.entity.dto.Activity;
import com.example.entity.dto.ActivityOrder;
import com.example.mapper.ActivityMapper;
import com.example.mapper.ActivityOrderMapper;
import com.example.service.ActivityService;
import com.example.service.impl.ActivityServiceImpl;
import com.example.utils.CacheUtils;
import com.example.utils.Const;
import com.example.utils.FlowUtils;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 抢活动全链路集成测试：真实 MySQL（Flyway 全量迁移）+ RabbitMQ（真实异步落单）+ Redis（真实预扣/幂等）。
 * 核心断言：并发抢购不超卖、订单状态与 Redis 回补一致、幂等与窗口校验生效。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(ActivityGrabIntegrationTest.TestContext.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ActivityGrabIntegrationTest {
    private static final int TOTAL_STOCK = 10;
    private static final int GRAB_USERS = 30;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("forum")
            .withUsername("forum")
            .withPassword("forum")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine")
            .withAdminUser("forum")
            .withAdminPassword("forum")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    @Autowired
    ActivityService activityService;
    @Autowired
    ActivityMapper activityMapper;
    @Autowired
    ActivityOrderMapper activityOrderMapper;
    @Autowired
    StringRedisTemplate template;
    @Autowired
    RabbitAdmin rabbitAdmin;

    @BeforeAll
    static void migrateSchema() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    @BeforeEach
    void resetState() throws Exception {
        activityOrderMapper.delete(Wrappers.emptyWrapper());
        activityMapper.delete(Wrappers.emptyWrapper());
        Set<String> keys = template.keys("activity:*");
        if (keys != null && !keys.isEmpty()) template.delete(keys);
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(Const.MQ_ACTIVITY_GRAB, false);
        rabbitAdmin.purgeQueue(Const.MQ_ACTIVITY_GRAB_ERROR, false);
    }

    /** 报名进行中的活动。 */
    private Activity openActivity(int totalStock) {
        Activity activity = new Activity();
        activity.setTitle("并发压测活动");
        activity.setDescription("集成测试");
        activity.setLocation("线上");
        activity.setActivityTime(new Date(System.currentTimeMillis() + 86400_000));
        activity.setTotalStock(totalStock);
        activity.setGrabbed(0);
        activity.setGrabStartTime(new Date(System.currentTimeMillis() - 3600_000));
        activity.setGrabEndTime(new Date(System.currentTimeMillis() + 3600_000));
        activityMapper.insert(activity);
        return activity;
    }

    /** 轮询等待消费端把全部订单落库（异步削峰的确定性等待）。 */
    private void awaitOrderCount(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Long count = activityOrderMapper.selectCount(Wrappers.emptyWrapper());
            if (count != null && count >= expected) return;
            Thread.sleep(100);
        }
        throw new AssertionError("等待订单落单超时，期望 " + expected + " 条");
    }

    private long countByStatus(int status) {
        return activityOrderMapper.selectCount(Wrappers.<ActivityOrder>query().eq("status", status));
    }

    @Test
    void concurrentGrabsNeverOversellAndSettleIntoOrders() throws Exception {
        Activity activity = openActivity(TOTAL_STOCK);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ActivityService.GrabResult>> results = IntStream.rangeClosed(1, GRAB_USERS)
                    .mapToObj(uid -> pool.submit(() -> {
                        start.await();
                        return activityService.grab(uid, activity.getId());
                    }))
                    .toList();
            start.countDown();
            List<ActivityService.GrabResult> grabbed = results.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();

            // 售罄后的请求在 Redis 层就被拒绝，不会进入 MQ——订单总数应恰好等于名额数
            awaitOrderCount(TOTAL_STOCK);

            // 名额恰好发满，绝不超卖
            assertEquals(TOTAL_STOCK, activityMapper.selectById(activity.getId()).getGrabbed());
            // 预扣通过并进入队列的请求恰好等于名额数，其余在 Redis 层被拒
            assertEquals(TOTAL_STOCK, grabbed.stream().filter(r -> r.code() == 200).count());
            assertEquals(GRAB_USERS - TOTAL_STOCK, grabbed.stream().filter(r -> r.code() == 400).count());
            // 订单状态：名额全部落「成功」，不存在 DB 兜底失败
            assertEquals(TOTAL_STOCK, countByStatus(ActivityOrder.STATUS_SUCCESS));
            assertEquals(0, countByStatus(ActivityOrder.STATUS_FAILED));
            // 回补后 Redis 库存归零，与 DB 一致
            assertEquals("0", template.opsForValue().get(Const.ACTIVITY_STOCK + activity.getId()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateGrabIsIdempotentAndReturnsCurrentStatus() throws Exception {
        Activity activity = openActivity(5);
        ActivityService.GrabResult first = activityService.grab(7, activity.getId());
        assertEquals(200, first.code());

        awaitOrderCount(1);

        // 防连点冷却过后再抢，幂等键命中，返回已有订单状态
        Thread.sleep(3100);
        ActivityService.GrabResult second = activityService.grab(7, activity.getId());
        assertEquals(200, second.code());
        assertTrue(second.message().contains("已报名成功"));
        assertEquals(1, activityOrderMapper.selectCount(Wrappers.<ActivityOrder>query().eq("uid", 7)));
    }

    @Test
    void dbFallbackMarksOrderFailedAndCompensatesRedisStock() throws Exception {
        // 人为制造 Redis 与 DB 的名额偏差：Redis 认为还有 5 个，DB 只有 2 个
        Activity activity = openActivity(2);
        template.opsForValue().set(Const.ACTIVITY_STOCK + activity.getId(), "5");

        List<ActivityService.GrabResult> results = IntStream.rangeClosed(100, 102)
                .mapToObj(uid -> activityService.grab(uid, activity.getId()))
                .toList();

        assertEquals(3, results.stream().filter(r -> r.code() == 200).count());
        awaitOrderCount(3);

        assertEquals(2, activityMapper.selectById(activity.getId()).getGrabbed());
        assertEquals(2, countByStatus(ActivityOrder.STATUS_SUCCESS));
        assertEquals(1, countByStatus(ActivityOrder.STATUS_FAILED));
        // DB 兜底拒绝的那份名额已在事务提交后回补 Redis：5 - 3 + 1 = 3
        assertEquals("3", template.opsForValue().get(Const.ACTIVITY_STOCK + activity.getId()));
    }

    @Test
    void grabOutsideWindowIsRejectedBeforeTouchingRedisOrBroker() {
        Activity activity = openActivity(5);
        activity.setGrabStartTime(new Date(System.currentTimeMillis() + 3600_000));
        activityMapper.updateById(activity);

        ActivityService.GrabResult result = activityService.grab(9, activity.getId());

        assertEquals(400, result.code());
        assertNull(template.opsForValue().get(Const.ACTIVITY_STOCK + activity.getId()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    @EnableTransactionManagement
    @MapperScan("com.example.mapper")
    @Import(RabbitConfiguration.class)
    static class TestContext {

        @Bean
        MysqlDataSource dataSource() {
            MysqlDataSource dataSource = new MysqlDataSource();
            dataSource.setUrl(MYSQL.getJdbcUrl());
            dataSource.setUser(MYSQL.getUsername());
            dataSource.setPassword(MYSQL.getPassword());
            return dataSource;
        }

        @Bean
        DataSourceTransactionManager transactionManager(MysqlDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        /** MyBatis-Plus 会话工厂：MybatisSqlSessionFactoryBean 默认挂 Spring 事务管理，
         * 监听器的 @Transactional（含回补的 afterCommit 时机）才能生效。 */
        @Bean
        SqlSessionFactory sqlSessionFactory(MysqlDataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factoryBean.setConfiguration(configuration);
            return factoryBean.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        ConnectionFactory rabbitConnectionFactory() {
            CachingConnectionFactory factory = new CachingConnectionFactory(
                    RABBIT.getHost(),
                    RABBIT.getAmqpPort()
            );
            factory.setUsername(RABBIT.getAdminUsername());
            factory.setPassword(RABBIT.getAdminPassword());
            factory.setPublisherConfirmType(ConfirmType.CORRELATED);
            factory.setPublisherReturns(true);
            return factory;
        }

        @Bean
        RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
            return new RabbitAdmin(connectionFactory);
        }

        @Bean
        RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            template.setMessageConverter(converter);
            template.setMandatory(true);
            return template;
        }

        @Bean
        org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory) {
            return new StringRedisTemplate(redisConnectionFactory);
        }

        @Bean
        FlowUtils flowUtils() {
            return new FlowUtils();
        }

        @Bean
        CacheUtils cacheUtils() {
            return new CacheUtils();
        }

        @Bean
        ActivityServiceImpl activityServiceImpl() {
            return new ActivityServiceImpl();
        }

        @Bean
        com.example.listener.ActivityGrabListener activityGrabListener(
                ActivityMapper activityMapper,
                ActivityOrderMapper activityOrderMapper,
                StringRedisTemplate template) {
            return new com.example.listener.ActivityGrabListener(activityMapper, activityOrderMapper, template);
        }
    }
}
