package com.example.agent.search;

import java.util.Set;

public record RankedTopic(
        TopicSearchHit topic,
        double score,
        Set<RetrievalSource> sources
) {
    public RankedTopic {
        sources = Set.copyOf(sources);
    }
}
