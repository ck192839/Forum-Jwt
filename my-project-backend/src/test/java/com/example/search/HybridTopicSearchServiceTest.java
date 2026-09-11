package com.example.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HybridTopicSearchServiceTest {

    @Test
    void combinesKeywordAndVectorResults() {
        TopicSearchHit keyword = new TopicSearchHit(1, "Keyword", "", 1, null);
        TopicSearchHit shared = new TopicSearchHit(2, "Shared", "", 1, null);
        TopicSearchHit vector = new TopicSearchHit(3, "Vector", "", 1, null);
        HybridTopicSearchService service = new HybridTopicSearchService(
                query -> List.of(keyword, shared),
                query -> List.of(shared, vector)
        );

        List<RankedTopic> result = service.search("network");

        assertEquals(List.of(2, 1, 3), result.stream().map(item -> item.topic().topicId()).toList());
    }

    @Test
    void fallsBackToKeywordResultsWhenVectorSearchFails() {
        List<TopicSearchHit> keyword = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(id -> new TopicSearchHit(id, "Keyword " + id, "", 1, null))
                .toList();
        HybridTopicSearchService service = new HybridTopicSearchService(
                query -> keyword,
                query -> {
                    throw new IllegalStateException("vector store unavailable");
                }
        );

        List<RankedTopic> result = service.search("network");

        assertEquals(List.of(1, 2, 3, 4, 5, 6), result.stream().map(item -> item.topic().topicId()).toList());
        assertEquals(List.of(RetrievalSource.KEYWORD), result.get(0).sources().stream().toList());
    }

    @Test
    void limitOverridesTheDefaultResultCount() {
        List<TopicSearchHit> keyword = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(id -> new TopicSearchHit(id, "Keyword " + id, "", 1, null))
                .toList();
        HybridTopicSearchService service = new HybridTopicSearchService(
                query -> keyword,
                query -> List.of()
        );

        assertEquals(8, service.search("network", 8).size());
        assertEquals(3, service.search("network", 3).size());
    }

    @Test
    void highlightTravelsThroughFusionUnchanged() {
        TopicSearchHit highlighted = new TopicSearchHit(1, "Title", "", 1, null,
                java.util.Map.of("intro", List.of("命中<em>片段</em>")));
        HybridTopicSearchService service = new HybridTopicSearchService(
                query -> List.of(highlighted),
                query -> List.of()
        );

        RankedTopic result = service.search("network").get(0);

        assertEquals(List.of("命中<em>片段</em>"), result.topic().highlight().get("intro"));
    }
}
