package com.example.agent.search;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiVectorTopicRetrieverTest {

    @Test
    void collapsesChunksFromTheSameTopic() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("topic-7-chunk-0", 7, "Wi-Fi", "First"),
                document("topic-7-chunk-1", 7, "Wi-Fi", "Second"),
                document("topic-8-chunk-0", 8, "Dorm network", "Third")
        ));
        SpringAiVectorTopicRetriever retriever = new SpringAiVectorTopicRetriever(vectorStore);

        List<TopicSearchHit> result = retriever.search("network");

        assertEquals(List.of(7, 8), result.stream().map(TopicSearchHit::topicId).toList());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    private Document document(String id, int topicId, String title, String excerpt) {
        return new Document(id, excerpt, Map.of(
                "topicId", topicId,
                "title", title,
                "excerpt", excerpt,
                "topicTypeId", 1,
                "visible", true
        ));
    }
}
