package com.example.search;

import java.util.Set;

/**
 * 融合后的排序结果：
 * - topic ：命中帖子
 * - score ：RRF 融合得分（分数越高越靠前）
 * - sources ：哪些检索源命中了该帖（KEYWORD / VECTOR，可同时两个）
 */
public record RankedTopic(
        TopicSearchHit topic,
        double score,
        Set<RetrievalSource> sources) {
    public RankedTopic {
        sources = Set.copyOf(sources);
    }
}
