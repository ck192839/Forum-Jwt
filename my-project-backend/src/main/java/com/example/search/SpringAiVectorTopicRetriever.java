package com.example.search;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量检索实现：基于 Spring AI VectorStore（底层 ES 向量索引 + Bailian text-embedding-v4）。
 *
 * 要点：
 * - 检索时用 filterExpression "visible == true" 过滤隐藏帖（向量库元数据里标记了 visible）
 * - 一个帖子可能被分块索引成多个向量文档，这里按 topicId 去重（putIfAbsent）
 * - 摘要用命中块自身的文本——命中帖尾的块时看到的就是该块内容，而非全文开头
 */
public class SpringAiVectorTopicRetriever implements VectorTopicRetriever {
    private static final int TOP_K = 20; // 检索前 20 条向量

    private final VectorStore vectorStore;

    public SpringAiVectorTopicRetriever(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public List<TopicSearchHit> search(String query) {
        // 构造检索请求：topK=20、不做相似度阈值过滤、只查可见帖子
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .similarityThresholdAll()
                .filterExpression("visible == true")
                .build();
        // 按 topicId 去重（分块导致一个帖子可能命中多条）
        Map<Integer, TopicSearchHit> topics = new LinkedHashMap<>();
        for (Document document : vectorStore.similaritySearch(request)) {
            TopicSearchHit hit = toHit(document);
            topics.putIfAbsent(hit.topicId(), hit);
        }
        return topics.values().stream().limit(TOP_K).toList();
    }

    /** 向量文档（含元数据）→ 统一命中结构。 */
    private TopicSearchHit toHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new TopicSearchHit(
                number(metadata, "topicId"),
                String.valueOf(metadata.getOrDefault("title", "")),
                document.getText(),
                number(metadata, "topicTypeId"),
                timestamp(metadata.get("topicTime")));
    }

    /** 元数据取值转 int（兼容 Number 与字符串两种存储形态）。 */
    private int number(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    /** 元数据里的时间转 epoch 毫秒（旧块缺失该字段时为 null）。 */
    private Long timestamp(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.parseLong(String.valueOf(value));
    }
}
