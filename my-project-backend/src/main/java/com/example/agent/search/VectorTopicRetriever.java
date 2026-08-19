package com.example.agent.search;

import java.util.List;

/**
 * 向量检索器接口（可替换点）。
 * 当前实现：{@link SpringAiVectorTopicRetriever}（基于 Spring AI VectorStore）。
 */
@FunctionalInterface
public interface VectorTopicRetriever {
    List<TopicSearchHit> search(String query);
}
