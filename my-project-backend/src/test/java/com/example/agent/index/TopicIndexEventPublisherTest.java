package com.example.agent.index;

import com.example.utils.Const;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TopicIndexEventPublisherTest {

    @Test
    void sendsOnlyAfterTheSurroundingTransactionCommits() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate);

        transactionTemplate().executeWithoutResult(status -> {
            publisher.upsert(7);
            verifyNoInteractions(rabbitTemplate);
        });

        verify(rabbitTemplate).convertAndSend(
                Const.MQ_TOPIC_INDEX,
                new TopicIndexEvent(7, TopicIndexAction.UPSERT)
        );
    }

    @Test
    void discardsTheEventWhenTheSurroundingTransactionRollsBack() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate);

        transactionTemplate().executeWithoutResult(status -> {
            publisher.delete(8);
            status.setRollbackOnly();
        });

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void sendsImmediatelyWhenThereIsNoTransaction() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate);

        publisher.upsert(9);

        verify(rabbitTemplate).convertAndSend(
                Const.MQ_TOPIC_INDEX,
                new TopicIndexEvent(9, TopicIndexAction.UPSERT)
        );
    }

    @Test
    void exposesRabbitFailuresInsteadOfReportingFalseSuccess() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicIndexEvent event = new TopicIndexEvent(10, TopicIndexAction.UPSERT);
        AmqpConnectException failure = new AmqpConnectException(new IllegalStateException("offline"));
        doThrow(failure).when(rabbitTemplate).convertAndSend(Const.MQ_TOPIC_INDEX, event);
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate);

        assertThrows(AmqpConnectException.class, () -> publisher.upsert(10));
    }

    @Test
    void exposesRabbitFailuresRaisedByTheAfterCommitCallback() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicIndexEvent event = new TopicIndexEvent(11, TopicIndexAction.DELETE);
        AmqpConnectException failure = new AmqpConnectException(new IllegalStateException("offline"));
        doThrow(failure).when(rabbitTemplate).convertAndSend(Const.MQ_TOPIC_INDEX, event);
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate);

        assertThrows(AmqpConnectException.class, () -> transactionTemplate()
                .executeWithoutResult(status -> publisher.delete(11)));
    }

    @Test
    void attemptsEveryEventAfterCommitEvenWhenAnEarlierSendFails() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TopicIndexEvent first = new TopicIndexEvent(12, TopicIndexAction.DELETE);
        TopicIndexEvent second = new TopicIndexEvent(13, TopicIndexAction.DELETE);
        AmqpConnectException failure = new AmqpConnectException(new IllegalStateException("offline"));
        doThrow(failure).when(rabbitTemplate).convertAndSend(Const.MQ_TOPIC_INDEX, first);
        TopicIndexEventPublisher publisher = new TopicIndexEventPublisher(rabbitTemplate);

        assertThrows(AmqpConnectException.class, () -> transactionTemplate().executeWithoutResult(status -> {
            publisher.delete(12);
            publisher.delete(13);
        }));

        verify(rabbitTemplate).convertAndSend(Const.MQ_TOPIC_INDEX, first);
        verify(rabbitTemplate).convertAndSend(Const.MQ_TOPIC_INDEX, second);
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
