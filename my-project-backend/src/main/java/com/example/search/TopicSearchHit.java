package com.example.search;

import java.util.List;
import java.util.Map;

/**
 * 一条检索命中（检索器输出、融合器输入的统一结构）：
 * - topicId ：帖子 id
 * - title ：标题
 * - excerpt ：摘要（供上层快速判断相关性）
 * - topicTypeId：板块 id
 * - topicTime ：发帖时间（epoch 毫秒，索引缺失时为 null）——供上层判断信息新旧，
 *               价格/活动/联系方式等会过时的内容尤其依赖它
 * - highlight ：高亮片段（title/intro 命中片段，含 <em> 标记）；向量路无高亮，为空 Map
 */
public record TopicSearchHit(
                int topicId,
                String title,
                String excerpt,
                int topicTypeId,
                Long topicTime,
                Map<String, List<String>> highlight) {

    public TopicSearchHit {
        highlight = highlight == null ? Map.of() : Map.copyOf(highlight);
    }

    /** 无高亮的命中（向量路/测试用），highlight 为空 Map，展示层回退到 title/excerpt。 */
    public TopicSearchHit(int topicId, String title, String excerpt, int topicTypeId, Long topicTime) {
        this(topicId, title, excerpt, topicTypeId, topicTime, Map.of());
    }
}
