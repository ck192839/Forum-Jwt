package com.example.agent.search;

import java.util.List;

public class HybridTopicSearchService {
    private static final int RESULT_LIMIT = 6;

    private final KeywordTopicRetriever keywordRetriever;
    private final VectorTopicRetriever vectorRetriever;

    public HybridTopicSearchService(
            KeywordTopicRetriever keywordRetriever,
            VectorTopicRetriever vectorRetriever
    ) {
        this.keywordRetriever = keywordRetriever;
        this.vectorRetriever = vectorRetriever;
    }

    public List<RankedTopic> search(String query) {
        List<TopicSearchHit> keywordHits = keywordRetriever.search(query);
        List<TopicSearchHit> vectorHits;
        try {
            vectorHits = vectorRetriever.search(query);
        } catch (RuntimeException ignored) {
            vectorHits = List.of();
        }
        return ReciprocalRankFusion.merge(keywordHits, vectorHits, RESULT_LIMIT);
    }
}
