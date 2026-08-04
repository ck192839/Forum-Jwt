package com.example.agent.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SpringAiVectorTopicRetriever implements VectorTopicRetriever {
    private static final int TOP_K = 20;

    private final VectorStore vectorStore;

    public SpringAiVectorTopicRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<TopicSearchHit> search(String query) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThresholdAll()
                .filterExpression("visible == true")
                .build();
        Map<Integer, TopicSearchHit> topics = new LinkedHashMap<>();
        for (Document document : vectorStore.similaritySearch(request)) {
            TopicSearchHit hit = toHit(document);
            topics.putIfAbsent(hit.topicId(), hit);
        }
        return topics.values().stream().limit(TOP_K).toList();
    }

    private TopicSearchHit toHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new TopicSearchHit(
                number(metadata, "topicId"),
                String.valueOf(metadata.getOrDefault("title", "")),
                String.valueOf(metadata.getOrDefault("excerpt", document.getText())),
                number(metadata, "topicTypeId")
        );
    }

    private int number(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
