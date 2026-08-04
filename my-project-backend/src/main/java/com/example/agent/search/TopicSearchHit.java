package com.example.agent.search;

public record TopicSearchHit(
        int topicId,
        String title,
        String excerpt,
        int topicTypeId
) {
}
