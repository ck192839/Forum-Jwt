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
                document("topic-7-chunk-0", 7, "Wi-Fi", "chunk text A"),
                document("topic-7-chunk-1", 7, "Wi-Fi", "chunk text B"),
                document("topic-8-chunk-0", 8, "Dorm network", "chunk text C")
        ));
        SpringAiVectorTopicRetriever retriever = new SpringAiVectorTopicRetriever(vectorStore);

        List<TopicSearchHit> result = retriever.search("network");

        assertEquals(List.of(7, 8), result.stream().map(TopicSearchHit::topicId).toList());
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
    }

    /** 摘要必须是首个命中块自身的文本（命中哪段看哪段），而不是帖子开头。 */
    @Test
    void excerptIsTheHitChunkTextItself() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                document("topic-9-chunk-2", 9, "Wi-Fi", "帖尾命中块的内容"),
                document("topic-9-chunk-0", 9, "Wi-Fi", "开头介绍")
        ));
        SpringAiVectorTopicRetriever retriever = new SpringAiVectorTopicRetriever(vectorStore);

        // ES 按相似度返回，尾块排在前面 → 去重后该帖的摘要应取尾块文本
        List<TopicSearchHit> result = retriever.search("信号强度");

        assertEquals("帖尾命中块的内容", result.get(0).excerpt());
    }

    private Document document(String id, int topicId, String title, String text) {
        return new Document(id, text, Map.of(
                "topicId", topicId,
                "title", title,
                "topicTypeId", 1,
                "visible", true
        ));
    }
}
