package com.example.agent.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridTopicSearchServiceTest {

    @Test
    void combinesKeywordAndVectorResults() {
        TopicSearchHit keyword = new TopicSearchHit(1, "Keyword", "", 1);
        TopicSearchHit shared = new TopicSearchHit(2, "Shared", "", 1);
        TopicSearchHit vector = new TopicSearchHit(3, "Vector", "", 1);
        HybridTopicSearchService service = new HybridTopicSearchService(
                query -> List.of(keyword, shared),
                query -> List.of(shared, vector)
        );

        List<RankedTopic> result = service.search("network");

        assertEquals(List.of(2, 1, 3), result.stream().map(item -> item.topic().topicId()).toList());
    }

    @Test
    void fallsBackToKeywordResultsWhenVectorSearchFails() {
        TopicSearchHit keyword = new TopicSearchHit(1, "Keyword", "", 1);
        HybridTopicSearchService service = new HybridTopicSearchService(
                query -> List.of(keyword),
                query -> {
                    throw new IllegalStateException("vector store unavailable");
                }
        );

        List<RankedTopic> result = service.search("network");

        assertEquals(List.of(1), result.stream().map(item -> item.topic().topicId()).toList());
        assertEquals(List.of(RetrievalSource.KEYWORD), result.get(0).sources().stream().toList());
    }
}
