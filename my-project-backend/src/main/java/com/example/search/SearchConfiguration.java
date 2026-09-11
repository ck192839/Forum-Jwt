package com.example.search;

import com.example.search.index.TopicChunker;
import com.example.search.index.TopicIndexRebuildService;
import com.example.search.index.TopicKeywordIndexer;
import com.example.search.index.TopicVectorIndexer;
import com.example.search.ElasticsearchKeywordTopicRetriever;
import com.example.search.HybridTopicSearchService;
import com.example.search.KeywordTopicRetriever;
import com.example.search.SpringAiVectorTopicRetriever;
import com.example.search.VectorTopicRetriever;
import com.example.mapper.TopicMapper;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 检索 / 向量索引相关 Bean 的装配。
 *
 * 组装两条链路：
 * 1. 检索链路：KeywordTopicRetriever + VectorTopicRetriever →
 * HybridTopicSearchService（关键词+向量混合检索，由检索质量回归门禁保护）
 * 2. 索引链路：TopicChunker → TopicVectorIndexer →
 * TopicIndexRebuildService（帖子向量索引的重建/写入）
 */
@Configuration
public class SearchConfiguration {

    /**
     * 文本分块器：把帖子正文切成适合向量化的块。
     * 参数：每块最大 2400 字符，块间重叠 400 字符（重叠保证切分边界处的语义不丢失）。
     */
    @Bean
    TopicChunker topicChunker() {
        return new TopicChunker(2400, 400);
    }

    /**
     * 关键词检索实现：基于 Elasticsearch 的 NativeQuery + highlight 片段。
     * 与站内搜索走的 TopicRepository 分离：这里多一层措辞容错，
     * 并返回命中片段供上层使用。
     */
    @Bean
    KeywordTopicRetriever keywordTopicRetriever(ElasticsearchOperations elasticsearchOperations) {
        return new ElasticsearchKeywordTopicRetriever(elasticsearchOperations);
    }

    /**
     * 向量检索实现：基于 Spring AI VectorStore（底层是 ES vector 索引 + Bailian embedding）。
     */
    @Bean
    VectorTopicRetriever vectorTopicRetriever(VectorStore vectorStore) {
        return new SpringAiVectorTopicRetriever(vectorStore);
    }

    /**
     * 混合检索服务：关键词结果 + 向量结果做 RRF 融合 → 去重 → top6。
     */
    @Bean
    HybridTopicSearchService hybridTopicSearchService(
            KeywordTopicRetriever keywordRetriever,
            VectorTopicRetriever vectorRetriever) {
        return new HybridTopicSearchService(keywordRetriever, vectorRetriever);
    }

    /** 向量索引器：把分块后的帖子写入向量库。 */
    @Bean
    TopicVectorIndexer topicVectorIndexer(VectorStore vectorStore, TopicChunker chunker) {
        return new TopicVectorIndexer(vectorStore, chunker);
    }

    /**
     * 索引重建专用单线程执行器（串行处理重建任务，避免并发写索引冲突）。
     * destroyMethod = "shutdown"：应用关闭时自动关闭线程池。
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService topicIndexExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    /** 索引重建服务：遍历全量帖子重新生成关键词 + 向量索引（后台管理入口调用）。 */
    @Bean
    TopicIndexRebuildService topicIndexRebuildService(
            TopicMapper topicMapper,
            TopicVectorIndexer indexer,
            TopicKeywordIndexer keywordIndexer,
            ExecutorService topicIndexExecutor) {
        return new TopicIndexRebuildService(topicMapper, indexer, keywordIndexer, topicIndexExecutor);
    }
}
