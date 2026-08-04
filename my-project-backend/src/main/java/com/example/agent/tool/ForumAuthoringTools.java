package com.example.agent.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.agent.search.HybridTopicSearchService;
import com.example.entity.dto.Topic;
import com.example.entity.dto.TopicType;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.utils.ProhibitedUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

public class ForumAuthoringTools {
    private final TopicTypeMapper topicTypeMapper;
    private final TopicMapper topicMapper;
    private final HybridTopicSearchService searchService;
    private final ProhibitedUtils prohibitedUtils;

    public ForumAuthoringTools(
            TopicTypeMapper topicTypeMapper,
            TopicMapper topicMapper,
            HybridTopicSearchService searchService,
            ProhibitedUtils prohibitedUtils
    ) {
        this.topicTypeMapper = topicTypeMapper;
        this.topicMapper = topicMapper;
        this.searchService = searchService;
        this.prohibitedUtils = prohibitedUtils;
    }

    @Tool(name = "list_topic_types", description = "List forum sections available for a new topic")
    public List<TopicTypeToolResult> listTopicTypes() {
        return topicTypeMapper.selectList(null).stream()
                .filter(type -> type.getId() != null && type.getId() > 0)
                .map(this::toToolResult)
                .toList();
    }

    @Tool(name = "search_similar_topics", description = "Search visible historical topics related to a draft")
    public List<SimilarTopicToolResult> searchSimilarTopics(
            @ToolParam(description = "Search phrase describing the planned topic") String query
    ) {
        String normalized = requireText(query, "query", 1000);
        return searchService.search(normalized).stream()
                .map(ranked -> new SimilarTopicToolResult(
                        ranked.topic().topicId(),
                        ranked.topic().title(),
                        ranked.topic().excerpt(),
                        ranked.topic().topicTypeId(),
                        ranked.sources()
                ))
                .toList();
    }

    @Tool(name = "read_public_topic", description = "Read the text of one visible public topic by id")
    public PublicTopicToolResult readPublicTopic(
            @ToolParam(description = "Positive forum topic id") int topicId
    ) {
        if (topicId <= 0) {
            throw new IllegalArgumentException("topicId must be positive");
        }
        Topic topic = topicMapper.selectById(topicId);
        if (topic == null || Integer.valueOf(1).equals(topic.getInvisible())) {
            return PublicTopicToolResult.notFound();
        }
        return new PublicTopicToolResult(
                true,
                topic.getId(),
                topic.getTitle(),
                topic.getType(),
                extractText(topic.getContent())
        );
    }

    @Tool(name = "validate_draft", description = "Validate a proposed draft against forum publishing constraints")
    public DraftValidationToolResult validateDraft(
            @ToolParam(description = "Proposed topic title") String title,
            @ToolParam(description = "Proposed forum section id") int topicTypeId,
            @ToolParam(description = "Proposed Markdown body text") String bodyMarkdown
    ) {
        List<String> errors = new ArrayList<>();
        if (!hasLength(title, 1, 30)) {
            errors.add("TITLE_LENGTH");
        }
        if (!hasLength(bodyMarkdown, 1, 20_000)) {
            errors.add("BODY_LENGTH");
        }
        if (topicTypeId <= 0 || topicTypeMapper.selectById(topicTypeId) == null) {
            errors.add("INVALID_TOPIC_TYPE");
        }
        if ((title != null && prohibitedUtils.containsProhibitedWord(title))
                || (bodyMarkdown != null && prohibitedUtils.containsProhibitedWord(bodyMarkdown))) {
            errors.add("PROHIBITED_CONTENT");
        }
        return new DraftValidationToolResult(errors.isEmpty(), errors);
    }

    private TopicTypeToolResult toToolResult(TopicType type) {
        return new TopicTypeToolResult(type.getId(), type.getName(), type.getDesc());
    }

    private String extractText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            JSONArray ops = JSONObject.parseObject(content).getJSONArray("ops");
            if (ops == null) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            for (Object item : ops) {
                Object insert = JSONObject.from(item).get("insert");
                if (insert instanceof String value) {
                    text.append(value);
                }
            }
            return text.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String requireText(String value, String field, int maxLength) {
        if (!hasLength(value, 1, maxLength)) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return value.trim();
    }

    private boolean hasLength(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        int length = normalized.codePointCount(0, normalized.length());
        return length >= min && length <= max;
    }
}
