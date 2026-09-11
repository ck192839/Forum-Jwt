package com.example.search.index;

import com.example.utils.Const;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TopicIndexEventPublisherTest {

    @Test
    void sendsOnlyAfterTheSurroundingTransactionCommits() {
        RabbitTemplate rabbitTemplate = confirmedRabbitTemplate();
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate, null, null, null);

        transactionTemplate().executeWithoutResult(status -> {
            publisher.upsert(7);
            verifyNoInteractions(rabbitTemplate);
        });

        verify(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX),
                eq(new TopicIndexEvent(7, TopicIndexAction.UPSERT)),
                any(CorrelationData.class)
        );
    }

    @Test
    void discardsTheEventWhenTheSurroundingTransactionRollsBack() {
        RabbitTemplate rabbitTemplate = confirmedRabbitTemplate();
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate, null, null, null);

        transactionTemplate().executeWithoutResult(status -> {
            publisher.delete(8);
            status.setRollbackOnly();
        });

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void sendsImmediatelyWhenThereIsNoTransaction() {
        RabbitTemplate rabbitTemplate = confirmedRabbitTemplate();
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate, null, null, null);

        publisher.upsert(9);

        verify(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX),
                eq(new TopicIndexEvent(9, TopicIndexAction.UPSERT)),
                any(CorrelationData.class)
        );
    }

    @Test
    void logsButDoesNotFailTheRequestWhenRabbitIsUnavailable() {
        RabbitTemplate rabbitTemplate = confirmedRabbitTemplate();
        TopicIndexEvent event = new TopicIndexEvent(10, TopicIndexAction.UPSERT);
        AmqpConnectException failure = new AmqpConnectException(new IllegalStateException("offline"));
        doThrow(failure).when(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX), eq(event), any(CorrelationData.class)
        );
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate, null, null, null);

        // 业务请求不能因为索引事件失败而报错（数据已落库，索引可重建兜底）
        assertDoesNotThrow(() -> publisher.upsert(10));
    }

    @Test
    void logsButDoesNotFailTheRequestWhenAfterCommitSendFails() {
        RabbitTemplate rabbitTemplate = confirmedRabbitTemplate();
        TopicIndexEvent event = new TopicIndexEvent(11, TopicIndexAction.DELETE);
        AmqpConnectException failure = new AmqpConnectException(new IllegalStateException("offline"));
        doThrow(failure).when(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX), eq(event), any(CorrelationData.class)
        );
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate, null, null, null);

        assertDoesNotThrow(() -> transactionTemplate().executeWithoutResult(status -> publisher.delete(11)));
    }

    @Test
    void attemptsEveryEventAfterCommitEvenWhenAnEarlierSendFails() {
        RabbitTemplate rabbitTemplate = confirmedRabbitTemplate();
        TopicIndexEvent first = new TopicIndexEvent(12, TopicIndexAction.DELETE);
        TopicIndexEvent second = new TopicIndexEvent(13, TopicIndexAction.DELETE);
        AmqpConnectException failure = new AmqpConnectException(new IllegalStateException("offline"));
        doThrow(failure).when(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX), eq(first), any(CorrelationData.class)
        );
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate, null, null, null);

        assertDoesNotThrow(() -> transactionTemplate().executeWithoutResult(status -> {
            publisher.delete(12);
            publisher.delete(13);
        }));

        verify(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX), eq(first), any(CorrelationData.class)
        );
        verify(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX), eq(second), any(CorrelationData.class)
        );
    }

    private RabbitTemplate confirmedRabbitTemplate() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(2);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                eq(Const.MQ_TOPIC_INDEX), any(TopicIndexEvent.class), any(CorrelationData.class)
        );
        return rabbitTemplate;
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(new TestTransactionManager());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
