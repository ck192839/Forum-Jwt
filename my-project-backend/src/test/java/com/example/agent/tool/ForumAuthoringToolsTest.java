package com.example.agent.tool;

import com.example.agent.search.HybridTopicSearchService;
import com.example.agent.search.KeywordTopicRetriever;
import com.example.agent.search.RetrievalSource;
import com.example.agent.search.TopicSearchHit;
import com.example.agent.search.VectorTopicRetriever;
import com.example.entity.dto.Topic;
import com.example.entity.dto.TopicType;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.utils.ProhibitedUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ForumAuthoringToolsTest {

    @Test
    void listsAvailableTopicTypes() {
        TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
        TopicType type = new TopicType();
        type.setId(3);
        type.setName("Help");
        type.setDesc("Questions and troubleshooting");
        when(typeMapper.selectList(null)).thenReturn(List.of(type));
        ForumAuthoringTools tools = tools(typeMapper, mock(TopicMapper.class), emptySearch());

        List<TopicTypeToolResult> result = tools.listTopicTypes();

        assertEquals(List.of(new TopicTypeToolResult(3, "Help", "Questions and troubleshooting")), result);
    }

    @Test
    void searchesSimilarTopicsUsingHybridRetrieval() {
        TopicSearchHit hit = new TopicSearchHit(42, "Previous guide", "Useful excerpt", 3);
        KeywordTopicRetriever keyword = query -> List.of(hit);
        VectorTopicRetriever vector = query -> List.of(hit);
        HybridTopicSearchService search = new HybridTopicSearchService(keyword, vector);
        ForumAuthoringTools tools = tools(mock(TopicTypeMapper.class), mock(TopicMapper.class), search);

        List<SimilarTopicToolResult> result = tools.searchSimilarTopics("network issue");

        assertEquals(1, result.size());
        assertEquals(42, result.get(0).topicId());
        assertEquals(Set.of(RetrievalSource.KEYWORD, RetrievalSource.VECTOR), result.get(0).sources());
    }

    @Test
    void readsOnlyVisibleTopicTextAndIgnoresImageEmbeds() {
        TopicMapper topicMapper = mock(TopicMapper.class);
        Topic topic = new Topic();
        topic.setId(42);
        topic.setTitle("Guide");
        topic.setType(3);
        topic.setInvisible(0);
        topic.setContent("{\"ops\":[{\"insert\":\"First line\\n\"},{\"insert\":{\"image\":\"/secret.png\"}},{\"insert\":\"Second line\"}]}");
        when(topicMapper.selectById(42)).thenReturn(topic);
        ForumAuthoringTools tools = tools(mock(TopicTypeMapper.class), topicMapper, emptySearch());

        PublicTopicToolResult result = tools.readPublicTopic(42);

        assertTrue(result.found());
        assertEquals("First line\nSecond line", result.bodyText());
        assertFalse(result.bodyText().contains("secret.png"));
    }

    @Test
    void neverReturnsHiddenOrMissingTopicContent() {
        TopicMapper topicMapper = mock(TopicMapper.class);
        Topic hidden = new Topic();
        hidden.setId(42);
        hidden.setInvisible(1);
        hidden.setContent("{\"ops\":[{\"insert\":\"private\"}]}");
        when(topicMapper.selectById(42)).thenReturn(hidden);
        when(topicMapper.selectById(99)).thenReturn(null);
        ForumAuthoringTools tools = tools(mock(TopicTypeMapper.class), topicMapper, emptySearch());

        assertFalse(tools.readPublicTopic(42).found());
        assertFalse(tools.readPublicTopic(99).found());
    }

    @Test
    void validatesDraftUsingForumLimitsTypesAndProhibitedWords() {
        TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
        ProhibitedUtils prohibited = mock(ProhibitedUtils.class);
        when(typeMapper.selectById(3)).thenReturn(new TopicType());
        when(prohibited.containsProhibitedWord("bad title")).thenReturn(true);
        ForumAuthoringTools tools = new ForumAuthoringTools(
                typeMapper,
                mock(TopicMapper.class),
                emptySearch(),
                prohibited,
                200,
                8000
        );

        DraftValidationToolResult invalid = tools.validateDraft("bad title", 3, "Body");
        DraftValidationToolResult valid = tools.validateDraft("Useful title", 3, "Body");

        assertFalse(invalid.valid());
        assertTrue(invalid.errors().contains("PROHIBITED_CONTENT"));
        assertTrue(valid.valid());
        assertEquals(List.of(), valid.errors());
    }

    @Test
    void truncatesLongSearchExcerptsAtTheConfiguredLimit() {
        TopicSearchHit hit = new TopicSearchHit(42, "Guide", "字".repeat(500), 3);
        HybridTopicSearchService search = new HybridTopicSearchService(query -> List.of(hit), query -> List.of());
        // excerpt 上限故意设为 100：验证截断生效
        ForumAuthoringTools tools = new ForumAuthoringTools(
                mock(TopicTypeMapper.class), mock(TopicMapper.class), search,
                mock(ProhibitedUtils.class), 100, 8000);

        List<SimilarTopicToolResult> result = tools.searchSimilarTopics("query");

        String excerpt = result.get(0).excerpt();
        assertEquals(101, excerpt.codePointCount(0, excerpt.length())); // 100 字 + "…"
        assertTrue(excerpt.endsWith("…"));
    }

    @Test
    void truncatesLongTopicBodyKeepingHeadAndTail() {
        TopicMapper topicMapper = mock(TopicMapper.class);
        Topic topic = new Topic();
        topic.setId(42);
        topic.setType(3);
        topic.setInvisible(0);
        topic.setContent("{\"ops\":[{\"insert\":\"" + "头".repeat(300) + "中间" + "尾".repeat(300) + "\"}]}");
        when(topicMapper.selectById(42)).thenReturn(topic);
        // 正文上限 100：头 50 + 尾 50，中间截断
        ForumAuthoringTools tools = new ForumAuthoringTools(
                mock(TopicTypeMapper.class), topicMapper, emptySearch(),
                mock(ProhibitedUtils.class), 200, 100);

        PublicTopicToolResult result = tools.readPublicTopic(42);

        String body = result.bodyText();
        assertTrue(body.startsWith("头".repeat(50)));
        assertTrue(body.endsWith("尾".repeat(50)));
        assertTrue(body.contains("[truncated]"));
        assertTrue(body.codePointCount(0, body.length()) < 120);
    }

    private ForumAuthoringTools tools(
            TopicTypeMapper typeMapper,
            TopicMapper topicMapper,
            HybridTopicSearchService search
    ) {
        return new ForumAuthoringTools(typeMapper, topicMapper, search, mock(ProhibitedUtils.class), 200, 8000);
    }

    private HybridTopicSearchService emptySearch() {
        return new HybridTopicSearchService(query -> List.of(), query -> List.of());
    }
}
