package com.example.agent.index;

import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpMessageReturnedException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
public class TopicIndexEventPublisher {
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    public TopicIndexEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void upsert(int topicId) {
        publish(new TopicIndexEvent(topicId, TopicIndexAction.UPSERT));
    }

    public void delete(int topicId) {
        publish(new TopicIndexEvent(topicId, TopicIndexAction.DELETE));
    }

    private void publish(TopicIndexEvent event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            PendingEvents pending = (PendingEvents) TransactionSynchronizationManager.getResource(this);
            if (pending != null) {
                pending.events.add(event);
                return;
            }
            PendingEvents newPending = new PendingEvents();
            newPending.events.add(event);
            TransactionSynchronizationManager.bindResource(this, newPending);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAll(newPending.events);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TopicIndexEventPublisher.this);
                }
            });
            return;
        }
        send(event);
    }

    private void sendAll(List<TopicIndexEvent> events) {
        AmqpException firstFailure = null;
        for (TopicIndexEvent event : events) {
            try {
                send(event);
            } catch (AmqpException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                } else {
                    firstFailure.addSuppressed(exception);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void send(TopicIndexEvent event) {
        try {
            sendConfirmed(event);
        } catch (AmqpException exception) {
            log.error("Unable to enqueue topic index event for topic {}", event.topicId(), exception);
            throw exception;
        }
    }

    private void sendConfirmed(TopicIndexEvent event) {
        CorrelationData correlation = new CorrelationData();
        rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, event, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (correlation.getReturned() != null) {
                throw new AmqpMessageReturnedException(
                        "Topic index event was returned by RabbitMQ",
                        correlation.getReturned()
                );
            }
            if (!confirm.isAck()) {
                throw new AmqpException("RabbitMQ rejected topic index event: " + confirm.getReason());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted while waiting for RabbitMQ publisher confirmation", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("Unable to confirm topic index event delivery", exception);
        }
    }

    private static final class PendingEvents {
        private final List<TopicIndexEvent> events = new ArrayList<>();
    }
}
