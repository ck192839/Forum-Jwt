package com.example.agent.search;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReciprocalRankFusion {
    private static final double RANK_CONSTANT = 60.0;

    private ReciprocalRankFusion() {
    }

    public static List<RankedTopic> merge(
            List<TopicSearchHit> keywordHits,
            List<TopicSearchHit> vectorHits,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        Map<Integer, Accumulator> merged = new LinkedHashMap<>();
        addRankedHits(merged, keywordHits, RetrievalSource.KEYWORD);
        addRankedHits(merged, vectorHits, RetrievalSource.VECTOR);

        return merged.values().stream()
                .map(Accumulator::toRankedTopic)
                .sorted(Comparator.comparingDouble(RankedTopic::score).reversed()
                        .thenComparingInt(item -> item.topic().topicId()))
                .limit(limit)
                .toList();
    }

    private static void addRankedHits(
            Map<Integer, Accumulator> merged,
            List<TopicSearchHit> hits,
            RetrievalSource source
    ) {
        for (int index = 0; index < hits.size(); index++) {
            TopicSearchHit hit = hits.get(index);
            Accumulator accumulator = merged.computeIfAbsent(hit.topicId(), ignored -> new Accumulator(hit));
            accumulator.score += 1.0 / (RANK_CONSTANT + index + 1);
            accumulator.sources.add(source);
        }
    }

    private static final class Accumulator {
        private final TopicSearchHit topic;
        private final EnumSet<RetrievalSource> sources = EnumSet.noneOf(RetrievalSource.class);
        private double score;

        private Accumulator(TopicSearchHit topic) {
            this.topic = topic;
        }

        private RankedTopic toRankedTopic() {
            return new RankedTopic(topic, score, sources);
        }
    }
}
