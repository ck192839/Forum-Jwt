package com.example.config;

import com.example.search.index.TopicIndexEventConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RabbitConfigurationContractTest {

    @Test
    void topicIndexListenerUsesItsDedicatedContainerFactory() throws Exception {
        Method handle = TopicIndexEventConsumer.class.getMethod(
                "handle",
                com.example.search.index.TopicIndexEvent.class
        );

        RabbitListener listener = handle.getAnnotation(RabbitListener.class);

        assertEquals("topicIndexRabbitListenerContainerFactory", listener.containerFactory());
    }

    @Test
    void dedicatedTopicIndexFactoryIsExposedAsABean() throws Exception {
        Method factory = RabbitConfiguration.class.getMethod(
                "topicIndexRabbitListenerContainerFactory",
                org.springframework.amqp.rabbit.connection.ConnectionFactory.class,
                org.springframework.amqp.support.converter.MessageConverter.class,
                org.springframework.amqp.rabbit.retry.MessageRecoverer.class
        );

        assertEquals(
                "topicIndexRabbitListenerContainerFactory",
                factory.getAnnotation(Bean.class).value()[0]
        );
    }

    @Test
    void topicIndexDeadLetterRecovererRepublishesWithExceptionDiagnostics() throws Exception {
        Method recoverer = RabbitConfiguration.class.getMethod(
                "topicIndexMessageRecoverer",
                RabbitTemplate.class
        );

        assertEquals("topicIndexMessageRecoverer", recoverer.getAnnotation(Bean.class).value()[0]);
        assertEquals(RepublishMessageRecoverer.class, recoverer.getReturnType());
    }

    @Test
    void exampleConfigurationDoesNotEnableRetryForEverySimpleListener() {
        assertNoGlobalRabbitRetry("/application.yml.example");
        assertNoGlobalRabbitRetry("/application-dev.yml.example");
    }

    @Test
    void exampleConfigurationEnablesPublisherConfirmsAndReturns() {
        assertPublisherReliability("/application.yml.example");
        assertPublisherReliability("/application-dev.yml.example");
    }

    private void assertNoGlobalRabbitRetry(String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            Map<String, Object> root = new Yaml().load(stream);
            Map<String, Object> spring = map(root.get("spring"));
            Map<String, Object> rabbit = map(spring.get("rabbitmq"));
            Map<String, Object> listener = map(rabbit.get("listener"));
            Map<String, Object> simple = map(listener.get("simple"));

            assertFalse(simple.containsKey("retry"), resource + " must not alter every listener");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertPublisherReliability(String resource) {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            Map<String, Object> root = new Yaml().load(stream);
            Map<String, Object> spring = map(root.get("spring"));
            Map<String, Object> rabbit = map(spring.get("rabbitmq"));

            assertEquals("correlated", rabbit.get("publisher-confirm-type"), resource);
            assertEquals(true, rabbit.get("publisher-returns"), resource);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value == null ? Map.of() : (Map<String, Object>) value;
    }
}
