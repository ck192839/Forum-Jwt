package com.example.agent.index;

import com.example.entity.dto.Topic;
import com.example.entity.es.TopicDocument;
import com.example.repository.TopicRepository;
import org.springframework.stereotype.Component;

/**
 * 关键词索引器：把帖子写入 ES 关键词索引（db_topic，站内搜索与 Agent 关键词检索共用）。
 *
 * 与 TopicVectorIndexer 的语义差异：
 * - 向量索引：隐藏帖 = 删向量（向量检索靠元数据 visible 过滤）
 * - 关键词索引：隐藏帖照常落库（invisible=true），检索时由
 *   ElasticsearchKeywordTopicRetriever 过滤——文档始终与数据库行一致，
 *   setTopicInvisible 来回切换时无需删除/重建。
 */
@Component
public class TopicKeywordIndexer {
    private final TopicRepository topicRepository;

    public TopicKeywordIndexer(TopicRepository topicRepository) {
        this.topicRepository = topicRepository;
    }

    /** 写入/更新一个帖子的关键词索引。 */
    public void index(Topic topic) {
        TopicDocument document = new TopicDocument();
        document.setId(topic.getId());
        document.setTitle(topic.getTitle());
        document.setIntro(topic.getIntro());
        document.setType(topic.getType());
        document.setUid(topic.getUid());
        document.setTime(topic.getTime());
        document.setTop(flag(topic.getTop()));
        document.setLocked(flag(topic.getLocked()));
        document.setInvisible(flag(topic.getInvisible()));
        topicRepository.save(document);
    }

    /** 删除一个帖子的关键词索引。 */
    public void delete(int topicId) {
        topicRepository.deleteById(topicId);
    }

    /** 清空全部关键词索引（全量重建前调用）。 */
    public void clear() {
        topicRepository.deleteAll();
    }

    /** Topic 里的 0/1 标记位转 Boolean（null 视为 false）。 */
    private boolean flag(Integer value) {
        return Integer.valueOf(1).equals(value);
    }
}
