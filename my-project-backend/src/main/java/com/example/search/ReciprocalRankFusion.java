package com.example.search;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 倒数排名融合（Reciprocal Rank Fusion）算法：把多路检索结果合并成一个有序列表。
 *
 * 思路：每个命中的得分 = Σ 1 / (K + rank)，K 是常数（默认 60），rank 是该帖在某个检索源里的名次。
 * - 名次越靠前得分越高；
 * - 同一帖子在多个源命中会累加得分（相当于「多源投票」）；
 * - 天然按 topicId 去重（LinkedHashMap + computeIfAbsent）。
 *
 * 每路最多取前 20 名参与融合，最终取前 limit 名。
 */
public final class ReciprocalRankFusion {
    private static final double RANK_CONSTANT = 60.0; // RRF 常数 K（平滑系数）
    private static final int MAX_HITS_PER_RETRIEVER = 20; // 每路最多参与融合的条数

    private ReciprocalRankFusion() {
    }

    /**
     * 融合关键词与向量两路结果。
     * 
     * @param limit 最终返回条数（≤0 返回空）
     */
    public static List<RankedTopic> merge(
            List<TopicSearchHit> keywordHits,
            List<TopicSearchHit> vectorHits,
            int limit) {
        if (limit <= 0) {
            return List.of();
        }

        // 按 topicId 累加得分与来源（LinkedHashMap 保持首次出现顺序）
        Map<Integer, Accumulator> merged = new LinkedHashMap<>();
        addRankedHits(merged, keywordHits, RetrievalSource.KEYWORD);
        addRankedHits(merged, vectorHits, RetrievalSource.VECTOR);

        // 按得分倒序（同分按 topicId 升序保证确定性），截取 limit 条
        return merged.values().stream()
                .map(Accumulator::toRankedTopic)
                .sorted(Comparator.comparingDouble(RankedTopic::score).reversed()
                        .thenComparingInt(item -> item.topic().topicId()))
                .limit(limit)
                .toList();
    }

    /** 把一路命中按名次累加进融合表。 */
    private static void addRankedHits(
            Map<Integer, Accumulator> merged,
            List<TopicSearchHit> hits,
            RetrievalSource source) {
        int hitCount = Math.min(hits.size(), MAX_HITS_PER_RETRIEVER);
        for (int index = 0; index < hitCount; index++) {
            TopicSearchHit hit = hits.get(index);
            Accumulator accumulator = merged.computeIfAbsent(hit.topicId(), ignored -> new Accumulator(hit));
            accumulator.score += 1.0 / (RANK_CONSTANT + index + 1);
            accumulator.sources.add(source);
        }
    }

    /** 内部累加器：记录帖子、累计得分与命中来源集合。 */
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
