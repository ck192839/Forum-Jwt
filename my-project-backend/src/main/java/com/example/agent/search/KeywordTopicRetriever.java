package com.example.agent.search;

import java.util.List;

@FunctionalInterface
public interface KeywordTopicRetriever {
    List<TopicSearchHit> search(String query);
}
