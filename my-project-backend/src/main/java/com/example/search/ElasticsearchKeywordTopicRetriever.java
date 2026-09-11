package com.example.search;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.example.entity.es.TopicDocument;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightFieldParameters;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightParameters;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/**
 * 关键词检索实现：基于 Spring Data Elasticsearch 的 ElasticsearchOperations。
 *
 * 流程：
 * 1. bool.should 检索（与站内搜索 findByTitleOrIntro 的字段一致，但多一层容错）：
 *    - title/intro 各一路 match_phrase（短语精确匹配）
 *    - 一路 multi_match 分词匹配（title+intro）+ minimum_should_match="1&lt;2"（措辞容错）
 * 2. 过滤掉隐藏帖（invisible = true 的帖子不进检索结果）
 * 3. 取前 20 条转成统一结构 TopicSearchHit
 *
 * 与站内搜索的差异：
 * - 站内搜索面向人（关键词本就贴合帖子表述），混合检索侧的查询可能是口语化自由短语
 *   （“哪家火锅好吃”≠“火锅店推荐”），纯 match_phrase 中文零召回风险高，故加分词路兜底；
 * - 混合检索侧需要 highlight 片段——上层看到的应是包含查询词的正文片段，
 *   而不是固定取帖子开头的 200 字；intro 无命中片段时由 noMatchSize 兜底返回头部文本。
 */
public class ElasticsearchKeywordTopicRetriever implements KeywordTopicRetriever {
    private static final int TOP_K = 20; // 每路取前 20（与 RRF 的 MAX_HITS_PER_RETRIEVER 一致）
    private static final int NO_MATCH_SIZE = 200; // 无命中片段时返回字段头部的长度
    private static final String MINIMUM_SHOULD_MATCH = "1<2"; // 单 token 查询要求 1 个，多 token 要求 2 个

    private final ElasticsearchOperations operations;

    public ElasticsearchKeywordTopicRetriever(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @Override
    public List<TopicSearchHit> search(String query) {
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> b
                        .should(phrases("title", query))
                        .should(phrases("intro", query))
                        .should(terms(query))))
                .withHighlightQuery(new HighlightQuery(new Highlight(
                        HighlightParameters.builder().withNoMatchSize(NO_MATCH_SIZE).build(),
                        List.of(
                                new HighlightField("title"),
                                new HighlightField("intro", HighlightFieldParameters.builder()
                                        .withNumberOfFragments(1)
                                        .build()))),
                        TopicDocument.class))
                .withMaxResults(TOP_K)
                .build();
        return operations.search(nativeQuery, TopicDocument.class).stream()
                // 安全过滤：隐藏帖不进检索结果
                .filter(hit -> !Boolean.TRUE.equals(hit.getContent().getInvisible()))
                .map(this::toHit)
                .limit(TOP_K)
                .toList();
    }

    /** ES 命中 → 统一命中结构：摘要优先取 intro 的 highlight 片段，缺失时回退 intro 头部。 */
    private TopicSearchHit toHit(SearchHit<TopicDocument> hit) {
        TopicDocument topic = hit.getContent();
        String excerpt = first(hit.getHighlightField("intro"));
        if (excerpt == null || excerpt.isBlank()) {
            excerpt = topic.getIntro();
        }
        Map<String, List<String>> highlight = new HashMap<>();
        List<String> titleFragments = hit.getHighlightField("title");
        if (!titleFragments.isEmpty()) {
            highlight.put("title", titleFragments);
        }
        List<String> introFragments = hit.getHighlightField("intro");
        if (!introFragments.isEmpty()) {
            highlight.put("intro", introFragments);
        }
        return new TopicSearchHit(
                topic.getId(),
                topic.getTitle(),
                excerpt,
                topic.getType(),
                time(topic.getTime()),
                highlight);
    }

    /** match_phrase 子查询：短语匹配（与站内搜索的 @Query 语义一致）。 */
    private Query phrases(String field, String query) {
        return Query.of(q -> q.matchPhrase(p -> p.field(field).query(query)));
    }

    /** match 分词子查询：multi_match 同时搜 title+intro，minimum_should_match 提精度。 */
    private Query terms(String query) {
        return Query.of(q -> q.multiMatch(m -> m
                .fields("title", "intro")
                .query(query)
                .minimumShouldMatch(MINIMUM_SHOULD_MATCH)));
    }

    private String first(List<String> fragments) {
        return (fragments == null || fragments.isEmpty()) ? null : fragments.get(0);
    }

    /** Date → epoch 毫秒（null 安全，索引缺时间字段时不阻塞检索）。 */
    private Long time(Date time) {
        return time == null ? null : time.getTime();
    }
}
