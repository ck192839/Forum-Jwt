package com.example.search.index;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicChunkerTest {

    @Test
    void splitsLongTextWithOverlapAndPrependsTheTitle() {
        String body = "a".repeat(2000) + "b".repeat(1000);

        List<String> chunks = new TopicChunker(2400, 400).split("Network help", body);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).startsWith("Network help\n\n"));
        assertTrue(chunks.get(1).startsWith("Network help\n\n" + "b".repeat(400)));
    }
}
