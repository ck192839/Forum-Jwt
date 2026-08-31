package com.example.agent.index;

import com.example.entity.dto.Topic;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 帖子向量索引器：把帖子写入向量库（ES 向量索引）。
 *
 * 流程：
 * 1. 先按 topicId 删除旧向量（幂等更新）
 * 2. 帖子是隐藏的 → 不写（相当于删除）
 * 3. 否则分块 → 每块一个 Document，元数据记录 topicId/title/topicTypeId/topicTime/visible
 *
 * 元数据里的 visible 供向量检索时过滤（SpringAiVectorTopicRetriever 用它过滤隐藏帖）；
 * 命中块的摘要直接取 Document 文本（即块自身内容），不在元数据里冗余存全文。
 */
public class TopicVectorIndexer {
    private final VectorStore vectorStore; // 向量库（ES）
    private final TopicChunker chunker; // 分块器

    public TopicVectorIndexer(VectorStore vectorStore, TopicChunker chunker) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
    }

    /** 索引（写入/更新）一个帖子。 */
    public void index(Topic topic) {
        // 先删旧的，保证幂等
        vectorStore.delete(filterFor(topic.getId()));
        // 隐藏帖不进入向量索引
        if (Integer.valueOf(1).equals(topic.getInvisible())) {
            return;
        }

        // 分块并构造 Document（每块带元数据）
        List<String> chunks = chunker.split(topic.getTitle(), topic.getIntro());
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("topicId", topic.getId());
            metadata.put("title", topic.getTitle());
            metadata.put("topicTypeId", topic.getType());
            if (topic.getTime() != null) {
                metadata.put("topicTime", topic.getTime().getTime()); // 检索结果透出时间，供模型判断信息新旧
            }
            metadata.put("visible", true); // 检索时按此过滤可见性
            documents.add(new Document(
                    "topic-" + topic.getId() + "-chunk-" + index, // 文档 id（稳定）
                    chunks.get(index),
                    metadata));
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
    }

    /** 删除某个帖子的全部向量。 */
    public void delete(int topicId) {
        vectorStore.delete(filterFor(topicId));
    }

    /** 清空全部可见帖子的向量（全量重建前调用）。 */
    public void clear() {
        vectorStore.delete(new FilterExpressionBuilder().eq("visible", true).build());
    }

    /** 构造按 topicId 过滤的表达式（删除指定帖子的向量）。 */
    private Filter.Expression filterFor(int topicId) {
        return new FilterExpressionBuilder().eq("topicId", topicId).build();
    }
}
