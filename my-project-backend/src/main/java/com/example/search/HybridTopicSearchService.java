package com.example.search;

import java.util.List;

/**
 * 混合检索服务：关键词+向量双路检索的融合实现。
 *
 * 流程：
 * 1. 关键词检索（Elasticsearch keyword 字段）取前 20
 * 2. 向量检索（ES vector + Bailian embedding）取前 20；失败时降级为空（不影响关键词）
 * 3. ReciprocalRankFusion 融合两条结果 → 去重 → 返回前 6
 *
 * 设计取舍：向量检索是「加分项」——它挂掉时纯关键词兜底仍可用，
 * 保证混合检索链路的高可用。
 */
public class HybridTopicSearchService {
    private static final int RESULT_LIMIT = 6; // 最终返回条数（引用最多 6 条）

    private final KeywordTopicRetriever keywordRetriever; // 关键词检索
    private final VectorTopicRetriever vectorRetriever; // 向量检索

    public HybridTopicSearchService(
            KeywordTopicRetriever keywordRetriever,
            VectorTopicRetriever vectorRetriever) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
    }

    /** 执行混合检索并融合排序。 */
    public List<RankedTopic> search(String query) {
        List<TopicSearchHit> keywordHits = keywordRetriever.search(query);
        List<TopicSearchHit> vectorHits;
        try {
            vectorHits = vectorRetriever.search(query);
        } catch (RuntimeException ignored) {
            // 向量检索不可用（ES 向量索引缺失/embedding 服务异常）→ 降级为纯关键词
            vectorHits = List.of();
        }
        return ReciprocalRankFusion.merge(keywordHits, vectorHits, RESULT_LIMIT);
    }
}
