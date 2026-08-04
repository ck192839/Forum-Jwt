package com.example.agent.tool;

import com.example.agent.search.RetrievalSource;

import java.util.Set;

public record SimilarTopicToolResult(
        int topicId,
        String title,
        String excerpt,
        int topicTypeId,
        Set<RetrievalSource> sources
) {
    public SimilarTopicToolResult {
        sources = Set.copyOf(sources);
    }
}
