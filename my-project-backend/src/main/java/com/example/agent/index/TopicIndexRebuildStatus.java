package com.example.agent.index;

import java.time.Instant;

public record TopicIndexRebuildStatus(
        boolean running,
        int total,
        int processed,
        int failed,
        Instant startedAt,
        Instant finishedAt
) {
    public static TopicIndexRebuildStatus idle() {
        return new TopicIndexRebuildStatus(false, 0, 0, 0, null, null);
    }
}
