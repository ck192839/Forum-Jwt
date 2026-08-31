package com.example.agent.tool;

import com.example.agent.search.RetrievalSource;

import java.util.Set;

/**
 * search_similar_topics 工具的单条结果：
 * - topicId / title / topicTypeId：帖子标识
 * - excerpt ：摘要（供模型快速判断相关性）
 * - topicTime：发帖时间（epoch 毫秒，可空）——让模型能判断信息新旧，避免引用过时内容
 * - sources ：命中的检索来源集合（KEYWORD / VECTOR，供排查与展示）
 */
public record SimilarTopicToolResult(
                int topicId,
                String title,
                String excerpt,
                int topicTypeId,
                Set<RetrievalSource> sources,
                Long topicTime) {
        public SimilarTopicToolResult {
                sources = Set.copyOf(sources);
        }
}
