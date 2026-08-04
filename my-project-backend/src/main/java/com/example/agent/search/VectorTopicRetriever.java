package com.example.agent.search;

import java.util.List;

@FunctionalInterface
public interface VectorTopicRetriever {
    List<TopicSearchHit> search(String query);
}
