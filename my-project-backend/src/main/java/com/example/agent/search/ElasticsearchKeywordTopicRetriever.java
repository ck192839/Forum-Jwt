package com.example.agent.search;

import com.example.entity.es.TopicDocument;
import com.example.repository.TopicRepository;
import org.springframework.data.elasticsearch.core.SearchHit;

import java.util.List;

/**
 * 关键词检索实现：基于 Spring Data Elasticsearch 的 TopicRepository。
 *
 * 流程：
 * 1. findByTitleOrIntro(query)：按标题或摘要关键词检索
 * 2. 过滤掉隐藏帖（invisible = true 的帖子对 Agent 不可见）
 * 3. 取前 20 条转成统一结构 TopicSearchHit
 */
public class ElasticsearchKeywordTopicRetriever implements KeywordTopicRetriever {
    private static final int TOP_K = 20; // 每路取前 20（与 RRF 的 MAX_HITS_PER_RETRIEVER 一致）

    private final TopicRepository topicRepository;

    public ElasticsearchKeywordTopicRetriever(TopicRepository topicRepository) {
        this.topicRepository = topicRepository;
    }

    @Override
    public List<TopicSearchHit> search(String query) {
        return topicRepository.findByTitleOrIntro(query).stream()
                .map(SearchHit::getContent)
                // 安全过滤：隐藏帖不进 Agent 视野
                .filter(topic -> !Boolean.TRUE.equals(topic.getInvisible()))
                .limit(TOP_K)
                .map(this::toHit)
                .toList();
    }

    /** ES 文档 → 统一命中结构。 */
    private TopicSearchHit toHit(TopicDocument topic) {
        return new TopicSearchHit(
                topic.getId(),
                topic.getTitle(),
                topic.getIntro(),
                topic.getType());
    }
}
