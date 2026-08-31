package com.example.agent.search;

/**
 * 一条检索命中（检索器输出、融合器输入的统一结构）：
 * - topicId ：帖子 id
 * - title ：标题
 * - excerpt ：摘要（供模型快速判断相关性）
 * - topicTypeId：板块 id
 * - topicTime ：发帖时间（epoch 毫秒，索引缺失时为 null）——供模型判断信息新旧，
 *               价格/活动/联系方式等会过时的内容尤其依赖它
 */
public record TopicSearchHit(
                int topicId,
                String title,
                String excerpt,
                int topicTypeId,
                Long topicTime) {
}
