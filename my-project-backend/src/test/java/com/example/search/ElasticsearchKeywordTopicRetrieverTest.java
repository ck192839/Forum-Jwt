package com.example.search;

import com.example.entity.es.TopicDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHitsImpl;
import org.springframework.data.elasticsearch.core.TotalHitsRelation;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElasticsearchKeywordTopicRetrieverTest {

    @Test
    void excerptPrefersIntroHighlightFragment() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.search(any(NativeQuery.class), eq(TopicDocument.class)))
                .thenReturn(hits(hit(topic(7, "Campus Wi-Fi", "开头很长……信号强度在帖尾"), Map.of(
                        "intro", List.of("……<em>信号强度</em>实测表……")
                ))));
        ElasticsearchKeywordTopicRetriever retriever = new ElasticsearchKeywordTopicRetriever(operations);

        List<TopicSearchHit> result = retriever.search("信号强度");

        assertEquals(1, result.size());
        assertEquals("……<em>信号强度</em>实测表……", result.get(0).excerpt());
    }

    /** title 命中而 intro 无片段时（noMatchSize 由 ES 兜底回头部），回退 intro 头部文本。 */
    @Test
    void fallsBackToIntroHeadWhenNoHighlightFragment() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.search(any(NativeQuery.class), eq(TopicDocument.class)))
                .thenReturn(hits(hit(topic(7, "Campus Wi-Fi", "帖子开头的介绍"), Map.of(
                        "title", List.of("<em>Campus</em> Wi-Fi")
                ))));
        ElasticsearchKeywordTopicRetriever retriever = new ElasticsearchKeywordTopicRetriever(operations);

        List<TopicSearchHit> result = retriever.search("Campus");

        assertEquals("帖子开头的介绍", result.get(0).excerpt());
    }

    @Test
    void filtersInvisibleTopics() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        TopicDocument invisible = topic(8, "Hidden", "不可见内容");
        invisible.setInvisible(true);
        when(operations.search(any(NativeQuery.class), eq(TopicDocument.class)))
                .thenReturn(hits(
                        hit(topic(7, "Visible", "可见内容"), Map.of()),
                        hit(invisible, Map.of())));
        ElasticsearchKeywordTopicRetriever retriever = new ElasticsearchKeywordTopicRetriever(operations);

        List<TopicSearchHit> result = retriever.search("内容");

        assertEquals(List.of(7), result.stream().map(TopicSearchHit::topicId).toList());
    }

    /** 显式 size=20：repository 时代 ES 默认 10 条导致 limit(20) 永远拿不满。 */
    @Test
    void requestsTopKFromElasticsearch() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        when(operations.search(any(NativeQuery.class), eq(TopicDocument.class))).thenReturn(hits());
        ElasticsearchKeywordTopicRetriever retriever = new ElasticsearchKeywordTopicRetriever(operations);

        retriever.search("network");

        ArgumentCaptor<NativeQuery> captor = ArgumentCaptor.forClass(NativeQuery.class);
        verify(operations).search(captor.capture(), eq(TopicDocument.class));
        assertEquals(20, captor.getValue().getMaxResults());
        assertFalse(captor.getValue().getHighlightQuery().isEmpty());
        assertTrue(captor.getValue().getQuery() != null);
    }

    private TopicDocument topic(int id, String title, String intro) {
        TopicDocument topic = new TopicDocument();
        topic.setId(id);
        topic.setTitle(title);
        topic.setIntro(intro);
        topic.setType(1);
        topic.setInvisible(false);
        return topic;
    }

    private SearchHit<TopicDocument> hit(TopicDocument topic, Map<String, List<String>> highlights) {
        return new SearchHit<>(null, String.valueOf(topic.getId()), null, 1.0f,
                null, highlights, null, null, null, null, topic);
    }

    @SafeVarargs
    private final SearchHits<TopicDocument> hits(SearchHit<TopicDocument>... hits) {
        return new SearchHitsImpl<>(hits.length, TotalHitsRelation.EQUAL_TO, 1.0f, null, null, null,
                List.of(hits), null, null, null);
    }
}
