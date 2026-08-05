package com.example.agent.index;

import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import com.example.utils.Const;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TopicIndexEventConsumer {
    private final TopicMapper topicMapper;
    private final TopicVectorIndexer indexer;

    public TopicIndexEventConsumer(TopicMapper topicMapper, TopicVectorIndexer indexer) {
        this.topicMapper = topicMapper;
        this.indexer = indexer;
    }

    @RabbitListener(
            queues = Const.MQ_TOPIC_INDEX,
            containerFactory = "topicIndexRabbitListenerContainerFactory"
    )
    public void handle(TopicIndexEvent event) {
        if (event.action() == TopicIndexAction.DELETE) {
            indexer.delete(event.topicId());
            return;
        }

        Topic topic = topicMapper.selectById(event.topicId());
        if (topic == null) {
            indexer.delete(event.topicId());
        } else {
            indexer.index(topic);
        }
    }
}
