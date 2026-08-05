package com.example.agent.index;

import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class TopicIndexEventPublisher {
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
            rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, event);
        } catch (AmqpException exception) {
            log.error("Unable to enqueue topic index event for topic {}", event.topicId(), exception);
            throw exception;
        }
    }

    private static final class PendingEvents {
        private final List<TopicIndexEvent> events = new ArrayList<>();
    }
}
