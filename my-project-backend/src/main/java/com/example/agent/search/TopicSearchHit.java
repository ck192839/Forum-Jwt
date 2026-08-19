package com.example.agent.search;

/**
 * 一条检索命中（检索器输出、融合器输入的统一结构）：
 * - topicId ：帖子 id
 * - title ：标题
 * - excerpt ：摘要（供模型快速判断相关性）
 * - topicTypeId：板块 id
 */
public record TopicSearchHit(
                int topicId,
                String title,
                String excerpt,
                int topicTypeId) {
}
