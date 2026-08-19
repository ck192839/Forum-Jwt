package com.example.agent.search;

import java.util.List;

/**
 * 关键词检索器接口（可替换点）。
 * 当前实现：{@link ElasticsearchKeywordTopicRetriever}（基于 ES keyword 字段）。
 * 功能接口（单一抽象方法），便于注入 mock 测试。
 */
@FunctionalInterface
public interface KeywordTopicRetriever {
    List<TopicSearchHit> search(String query);
}
