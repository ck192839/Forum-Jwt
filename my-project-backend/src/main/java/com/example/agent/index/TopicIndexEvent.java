package com.example.agent.index;

/**
 * 索引事件：论坛业务在帖子变更后发出，由 RabbitMQ 队列异步消费。
 * - topicId：帖子 id
 * - action ：UPSERT（写入/更新）或 DELETE（删除）
 */
public record TopicIndexEvent(int topicId, TopicIndexAction action) {
}
