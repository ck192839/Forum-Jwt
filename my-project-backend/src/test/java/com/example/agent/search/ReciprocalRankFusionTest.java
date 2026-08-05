package com.example.agent.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {

    @Test
    void ranksTopicsFoundByBothRetrieversFirstAndDeduplicatesThem() {
        TopicSearchHit first = new TopicSearchHit(1, "Dorm network", "How to connect", 1);
        TopicSearchHit shared = new TopicSearchHit(2, "Campus Wi-Fi", "Connection guide", 1);
        TopicSearchHit vectorOnly = new TopicSearchHit(3, "Network troubleshooting", "Common fixes", 1);

        List<RankedTopic> result = ReciprocalRankFusion.merge(
                List.of(first, shared),
                List.of(shared, vectorOnly),
                6
        );

        assertEquals(List.of(2, 1, 3), result.stream().map(item -> item.topic().topicId()).toList());
        assertEquals(Set.of(RetrievalSource.KEYWORD, RetrievalSource.VECTOR), result.get(0).sources());
    }

    @Test
    void limitsTheNumberOfReturnedTopics() {
        List<TopicSearchHit> keywordHits = List.of(
                new TopicSearchHit(1, "One", "", 1),
                new TopicSearchHit(2, "Two", "", 1),
                new TopicSearchHit(3, "Three", "", 1)
        );

        List<RankedTopic> result = ReciprocalRankFusion.merge(keywordHits, List.of(), 2);

        assertEquals(List.of(1, 2), result.stream().map(item -> item.topic().topicId()).toList());
    }

    @Test
    void considersOnlyTheFirstTwentyHitsFromEachRetriever() {
        List<TopicSearchHit> keywordHits = hits(1, 21);
        List<TopicSearchHit> vectorHits = hits(101, 21);

        List<RankedTopic> result = ReciprocalRankFusion.merge(keywordHits, vectorHits, 50);

        assertEquals(40, result.size());
        assertTrue(result.stream().noneMatch(item -> item.topic().topicId() == 21));
        assertTrue(result.stream().noneMatch(item -> item.topic().topicId() == 121));
    }

    private List<TopicSearchHit> hits(int firstId, int count) {
        return IntStream.range(firstId, firstId + count)
                .mapToObj(id -> new TopicSearchHit(id, "Topic " + id, "", 1))
                .toList();
    }
}
