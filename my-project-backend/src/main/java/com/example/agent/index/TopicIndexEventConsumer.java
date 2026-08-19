package com.example.agent.index;

import com.example.entity.dto.Topic;
import com.example.mapper.TopicMapper;
import com.example.utils.Const;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 索引事件消费者：监听 RabbitMQ 队列，执行实际的索引写入/删除。
 *
 * 处理规则：
 * - DELETE 动作 → 直接删向量索引
 * - UPSERT 动作 → 重新查库：
 * - 帖子还在 → 重建索引（隐藏帖由 indexer 内部处理为删除）
 * - 帖子已不存在 → 删索引
 *
 * 注意：监听用的是独立容器工厂 topicIndexRabbitListenerContainerFactory
 * （独立线程、独立重试配置，重试耗尽后进死信队列）。
 */
@Component
public class TopicIndexEventConsumer {
    private final TopicMapper topicMapper; // 帖子查询
    private final TopicVectorIndexer indexer; // 向量索引写入

    public TopicIndexEventConsumer(TopicMapper topicMapper, TopicVectorIndexer indexer) {
        this.topicMapper = topicMapper;
        this.indexer = indexer;
    }

    @RabbitListener(queues = Const.MQ_TOPIC_INDEX, containerFactory = "topicIndexRabbitListenerContainerFactory")
    public void handle(TopicIndexEvent event) {
        // 删除动作：直接删索引
        if (event.action() == TopicIndexAction.DELETE) {
            indexer.delete(event.topicId());
            return;
        }
        // 写入动作：以数据库为准
        Topic topic = topicMapper.selectById(event.topicId());
        if (topic == null) {
            indexer.delete(event.topicId());
        } else {
            indexer.index(topic);
        }
    }
}
