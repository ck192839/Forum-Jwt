package com.example.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流 Lua 脚本真实 Redis 集成测试。
 * 核心断言：无 JVM 锁的情况下，并发调用同一 key 的计数依旧精确——
 * 恰好 frequency 个请求放行（旧 synchronized 锁保证的属性，由 Lua 原子性接棒）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(FlowUtilsLuaIntegrationTest.TestContext.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FlowUtilsLuaIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    @Autowired
    FlowUtils flowUtils;
    @Autowired
    StringRedisTemplate template;

    @BeforeEach
    void resetKeys() {
        Set<String> keys = template.keys("flowtest:*");
        if (keys != null && !keys.isEmpty()) template.delete(keys);
    }

    @Test
    void counterCheckAllowsExactlyFrequencyRequestsInWindow() {
        int frequency = 5;
        long allowed = IntStream.rangeClosed(1, 8)
                .filter(i -> flowUtils.limitPeriodCounterCheck("flowtest:counter", frequency, 30))
                .count();
        assertEquals(frequency, allowed);
        // 计数继续累加到 8（被拒的请求同样计数，与旧实现一致）
        assertEquals("8", template.opsForValue().get("flowtest:counter"));
    }

    @Test
    void concurrentCallsOnSameKeyAllowExactlyFrequencyRequests() throws Exception {
        int threads = 100;
        int frequency = 10;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = IntStream.rangeClosed(1, threads)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return flowUtils.limitPeriodCounterCheck("flowtest:conc", frequency, 30);
                    }))
                    .toList();
            start.countDown();
            long allowed = results.stream()
                    .map(f -> {
                        try {
                            return f.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .filter(Boolean::booleanValue)
                    .count();
            assertEquals(frequency, allowed, "无锁脚本必须保证计数精确：恰好 frequency 个放行");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onceCheckBlocksSubsequentCallsWithinCooldown() {
        assertTrue(flowUtils.limitOnceCheck("flowtest:once", 3));
        assertFalse(flowUtils.limitOnceCheck("flowtest:once", 3));
        Long ttl = template.getExpire("flowtest:once");
        assertTrue(ttl != null && ttl > 0 && ttl <= 3);
    }

    @Test
    void periodCheckSetsBlockKeyAndStopsCountingWhileBlocked() {
        String counter = "flowtest:pc-counter";
        String block = "flowtest:pc-block";
        // 前两次放行（frequency=2），第三次触发封禁
        assertTrue(flowUtils.limitPeriodCheck(counter, block, 30, 2, 10));
        assertTrue(flowUtils.limitPeriodCheck(counter, block, 30, 2, 10));
        assertFalse(flowUtils.limitPeriodCheck(counter, block, 30, 2, 10));
        // 封禁键为空值占位（与旧实现一致），存在即可
        assertEquals("", template.opsForValue().get(block));
        // 触发封禁的请求本身计到 frequency+1（旧实现一致）；封禁期内再请求：拒绝且不再累加
        assertFalse(flowUtils.limitPeriodCheck(counter, block, 30, 2, 10));
        assertEquals("3", template.opsForValue().get(counter));
        assertTrue(template.getExpire(block) > 0);
    }

    @Test
    void upgradeCheckReArmsLongerWindowOnBreach() {
        String key = "flowtest:upgrade";
        assertTrue(flowUtils.limitOnceUpgradeCheck(key, 2, 2, 60));
        assertTrue(flowUtils.limitOnceUpgradeCheck(key, 2, 2, 60));
        assertFalse(flowUtils.limitOnceUpgradeCheck(key, 2, 2, 60));
        // 超频后 TTL 被重置为升级时间 60 秒（旧语义：重置窗口）
        Long ttl = template.getExpire(key);
        assertTrue(ttl != null && ttl > 55, "TTL 应接近升级时间 60s，实际 " + ttl);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestContext {
        @Bean
        RedisConnectionFactory redisConnectionFactory() {
            return new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean
        FlowUtils flowUtils() {
            return new FlowUtils();
        }
    }
}
