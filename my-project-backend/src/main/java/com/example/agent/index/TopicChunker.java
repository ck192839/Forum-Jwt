package com.example.agent.index;

import java.util.ArrayList;
import java.util.List;

public class TopicChunker {
    private final int chunkSize;
    private final int overlap;

    public TopicChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("chunkSize must be positive and overlap must be smaller");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<String> split(String title, String body) {
        String safeTitle = title == null ? "" : title.trim();
        String safeBody = body == null ? "" : body.trim();
        if (safeBody.isEmpty()) {
            return safeTitle.isEmpty() ? List.of() : List.of(safeTitle);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < safeBody.length()) {
            int end = Math.min(start + chunkSize, safeBody.length());
            String prefix = safeTitle.isEmpty() ? "" : safeTitle + "\n\n";
            chunks.add(prefix + safeBody.substring(start, end));
            if (end == safeBody.length()) {
                break;
            }
            start = end - overlap;
        }
        return chunks;
    }
}
