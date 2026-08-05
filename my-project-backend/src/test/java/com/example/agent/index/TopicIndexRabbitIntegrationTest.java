package com.example.agent.index;

import com.example.config.RabbitConfiguration;
import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import com.example.utils.Const;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@SpringJUnitConfig(TopicIndexRabbitIntegrationTest.TestRabbitContext.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TopicIndexRabbitIntegrationTest {
    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13.7-management-alpine")
            .withAdminUser("forum")
            .withAdminPassword("forum")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RabbitAdmin rabbitAdmin;

    @Autowired
    TopicMapper topicMapper;

    @Autowired
    TopicVectorIndexer indexer;

    @BeforeEach
    void resetQueuesAndCollaborators() {
        reset(topicMapper, indexer);
        rabbitAdmin.initialize();
        rabbitAdmin.purgeQueue(Const.MQ_TOPIC_INDEX, false);
        rabbitAdmin.purgeQueue(Const.MQ_TOPIC_INDEX_ERROR, false);
    }

    @Test
    void routesSuccessfulUpsertAndDeleteEventsThroughRabbitMq() {
        Topic topic = new Topic();
        topic.setId(7);
        when(topicMapper.selectById(7)).thenReturn(topic);

        rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, new TopicIndexEvent(7, TopicIndexAction.UPSERT));
        verify(indexer, timeout(10_000)).index(topic);

        rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, new TopicIndexEvent(8, TopicIndexAction.DELETE));
        verify(indexer, timeout(10_000)).delete(8);
    }

    @Test
    void retriesThreeTimesThenDeadLettersTheOriginalEventWithDiagnostics() {
        AtomicInteger attempts = new AtomicInteger();
        when(topicMapper.selectById(99)).thenAnswer(invocation -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        });

        rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, new TopicIndexEvent(99, TopicIndexAction.UPSERT));

        Message deadLetter = rabbitTemplate.receive(Const.MQ_TOPIC_INDEX_ERROR, 15_000);
        assertNotNull(deadLetter, "failed event should arrive in the dedicated DLQ");
        assertEquals(3, attempts.get());
        TopicIndexEvent original = (TopicIndexEvent) rabbitTemplate.getMessageConverter().fromMessage(deadLetter);
        assertEquals(new TopicIndexEvent(99, TopicIndexAction.UPSERT), original);
        Map<String, Object> headers = deadLetter.getMessageProperties().getHeaders();
        assertTrue(String.valueOf(headers.get("x-exception-message")).contains("database unavailable"));
        assertTrue(String.valueOf(headers.get("x-exception-stacktrace")).contains("IllegalStateException"));
        assertEquals(Const.MQ_TOPIC_INDEX, headers.get("x-original-routingKey"));
        assertTrue(headers.containsKey("__TypeId__"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    @Import(RabbitConfiguration.class)
    static class TestRabbitContext {
        @Bean
        ConnectionFactory rabbitConnectionFactory() {
            CachingConnectionFactory factory = new CachingConnectionFactory(
                    RABBIT.getHost(),
                    RABBIT.getAmqpPort()
            );
            factory.setUsername(RABBIT.getAdminUsername());
            factory.setPassword(RABBIT.getAdminPassword());
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
            template.setReceiveTimeout(15_000);
            return template;
        }

        @Bean
        TopicMapper topicMapper() {
            return mock(TopicMapper.class);
        }

        @Bean
        TopicVectorIndexer topicVectorIndexer() {
            return mock(TopicVectorIndexer.class);
        }

        @Bean
        TopicIndexEventConsumer topicIndexEventConsumer(TopicMapper mapper, TopicVectorIndexer indexer) {
            return new TopicIndexEventConsumer(mapper, indexer);
        }
    }
}
