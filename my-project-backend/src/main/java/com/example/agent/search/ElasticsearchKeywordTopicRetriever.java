package com.example.agent.search;

import com.example.entity.es.TopicDocument;
import com.example.repository.TopicRepository;
import org.springframework.data.elasticsearch.core.SearchHit;

import java.util.List;

public class ElasticsearchKeywordTopicRetriever implements KeywordTopicRetriever {
    private static final int TOP_K = 20;

    private final TopicRepository topicRepository;

    public ElasticsearchKeywordTopicRetriever(TopicRepository topicRepository) {
        this.topicRepository = topicRepository;
    }

    @Override
    public List<TopicSearchHit> search(String query) {
        return topicRepository.findByTitleOrIntro(query).stream()
                .map(SearchHit::getContent)
                .filter(topic -> !Boolean.TRUE.equals(topic.getInvisible()))
                .limit(TOP_K)
                .map(this::toHit)
                .toList();
    }

    private TopicSearchHit toHit(TopicDocument topic) {
        return new TopicSearchHit(
                topic.getId(),
                topic.getTitle(),
                topic.getIntro(),
                topic.getType()
        );
    }
}
