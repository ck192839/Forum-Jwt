package com.example.agent.index;

import com.example.entity.dto.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TopicVectorIndexerTest {

    @Test
    void replacesExistingChunksForAVisibleTopic() {
        VectorStore vectorStore = mock(VectorStore.class);
        TopicVectorIndexer indexer = new TopicVectorIndexer(vectorStore, new TopicChunker(2400, 400));
        Topic topic = topic(42, 0);

        indexer.index(topic);

        verify(vectorStore).delete(any(Filter.Expression.class));
        ArgumentCaptor<List<Document>> documents = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(documents.capture());
        assertEquals(42, documents.getValue().get(0).getMetadata().get("topicId"));
        assertEquals("Campus Wi-Fi", documents.getValue().get(0).getMetadata().get("title"));
    }

    @Test
    void removesHiddenTopicsWithoutAddingDocuments() {
        VectorStore vectorStore = mock(VectorStore.class);
        TopicVectorIndexer indexer = new TopicVectorIndexer(vectorStore, new TopicChunker(2400, 400));

        indexer.index(topic(42, 1));

        verify(vectorStore).delete(any(Filter.Expression.class));
        verify(vectorStore, never()).add(anyList());
    }

    private Topic topic(int id, int invisible) {
        Topic topic = new Topic();
        topic.setId(id);
        topic.setTitle("Campus Wi-Fi");
        topic.setIntro("How to connect to the campus network");
        topic.setContent("{\"ops\":[{\"insert\":\"Connection details\"}]}");
        topic.setType(1);
        topic.setInvisible(invisible);
        return topic;
    }
}
