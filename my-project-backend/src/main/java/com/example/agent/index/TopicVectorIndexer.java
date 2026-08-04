package com.example.agent.index;

import com.example.entity.dto.Topic;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TopicVectorIndexer {
    private final VectorStore vectorStore;
    private final TopicChunker chunker;

    public TopicVectorIndexer(VectorStore vectorStore, TopicChunker chunker) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
    }

    public void index(Topic topic) {
        vectorStore.delete(filterFor(topic.getId()));
        if (Integer.valueOf(1).equals(topic.getInvisible())) {
            return;
        }

        List<String> chunks = chunker.split(topic.getTitle(), topic.getIntro());
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("topicId", topic.getId());
            metadata.put("title", topic.getTitle());
            metadata.put("excerpt", topic.getIntro());
            metadata.put("topicTypeId", topic.getType());
            metadata.put("visible", true);
            documents.add(new Document(
                    "topic-" + topic.getId() + "-chunk-" + index,
                    chunks.get(index),
                    metadata
            ));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    public void delete(int topicId) {
        vectorStore.delete(filterFor(topicId));
    }

    private Filter.Expression filterFor(int topicId) {
        return new FilterExpressionBuilder().eq("topicId", topicId).build();
    }
}
