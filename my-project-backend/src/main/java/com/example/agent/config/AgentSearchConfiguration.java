package com.example.agent.config;

import com.example.agent.index.TopicChunker;
import com.example.agent.index.TopicIndexRebuildService;
import com.example.agent.index.TopicVectorIndexer;
import com.example.agent.search.ElasticsearchKeywordTopicRetriever;
import com.example.agent.search.HybridTopicSearchService;
import com.example.agent.search.KeywordTopicRetriever;
import com.example.agent.search.SpringAiVectorTopicRetriever;
import com.example.agent.search.VectorTopicRetriever;
import com.example.repository.TopicRepository;
import com.example.mapper.TopicMapper;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AgentSearchConfiguration {

    @Bean
    TopicChunker topicChunker() {
        return new TopicChunker(2400, 400);
    }

    @Bean
    KeywordTopicRetriever keywordTopicRetriever(TopicRepository topicRepository) {
        return new ElasticsearchKeywordTopicRetriever(topicRepository);
    }

    @Bean
    VectorTopicRetriever vectorTopicRetriever(VectorStore vectorStore) {
        return new SpringAiVectorTopicRetriever(vectorStore);
    }

    @Bean
    HybridTopicSearchService hybridTopicSearchService(
            KeywordTopicRetriever keywordRetriever,
            VectorTopicRetriever vectorRetriever
    ) {
        return new HybridTopicSearchService(keywordRetriever, vectorRetriever);
    }

    @Bean
    TopicVectorIndexer topicVectorIndexer(VectorStore vectorStore, TopicChunker chunker) {
        return new TopicVectorIndexer(vectorStore, chunker);
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService topicIndexExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    @Bean
    TopicIndexRebuildService topicIndexRebuildService(
            TopicMapper topicMapper,
            TopicVectorIndexer indexer,
            ExecutorService topicIndexExecutor
    ) {
        return new TopicIndexRebuildService(topicMapper, indexer, topicIndexExecutor);
    }
}
