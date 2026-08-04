package com.example.agent.index;

import com.example.utils.Const;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

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
        try {
            rabbitTemplate.convertAndSend(Const.MQ_TOPIC_INDEX, event);
        } catch (AmqpException exception) {
            log.error("Unable to enqueue topic index event for topic {}", event.topicId(), exception);
        }
    }
}
