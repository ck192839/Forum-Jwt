package com.example.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class AgentTerminalResultParser {
    private static final Set<String> QUESTION_FIELDS = Set.of("type", "question");
    private static final Set<String> DRAFT_FIELDS = Set.of(
            "type", "title", "topicTypeId", "bodyMarkdown", "citations", "basedOnEditorVersion"
    );
    private static final Set<String> CITATION_FIELDS = Set.of("topicId", "title");

    private final ObjectMapper objectMapper;

    public AgentTerminalResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AgentTerminalResult parse(String raw, int editorVersion, Set<Integer> knownTopicIds) {
        if (raw == null || !raw.trim().startsWith("{") || !raw.trim().endsWith("}")) {
            throw invalid("Agent output must be a JSON object without code fences");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            throw new AgentOutputValidationException("Agent output is not valid JSON", exception);
        }
        if (!root.isObject()) {
            throw invalid("Agent output must be a JSON object");
        }
        String type = requiredText(root, "type");
        return switch (type) {
            case "QUESTION" -> parseQuestion(root);
            case "DRAFT" -> parseDraft(root, editorVersion, knownTopicIds);
            default -> throw invalid("Unsupported Agent result type: " + type);
        };
    }

    private AgentQuestionResult parseQuestion(JsonNode root) {
        requireExactFields(root, QUESTION_FIELDS);
        String question = requiredText(root, "question").trim();
        requireLength(question, 1, 1000, "question");
        return new AgentQuestionResult(question);
    }

    private AgentDraftResult parseDraft(JsonNode root, int editorVersion, Set<Integer> knownTopicIds) {
        requireExactFields(root, DRAFT_FIELDS);
        String title = requiredText(root, "title").trim();
        String body = requiredText(root, "bodyMarkdown").trim();
        requireLength(title, 1, 30, "title");
        requireLength(body, 1, 20_000, "bodyMarkdown");
        int topicTypeId = requiredPositiveInt(root, "topicTypeId");
        int basedOnEditorVersion = requiredNonNegativeInt(root, "basedOnEditorVersion");
        if (basedOnEditorVersion != editorVersion) {
            throw invalid("Draft is based on a stale editor version");
        }
        List<AgentCitation> citations = parseCitations(root.get("citations"), knownTopicIds);
        return new AgentDraftResult(title, topicTypeId, body, citations, basedOnEditorVersion);
    }

    private List<AgentCitation> parseCitations(JsonNode node, Set<Integer> knownTopicIds) {
        if (node == null || !node.isArray() || node.size() > 6) {
            throw invalid("citations must be an array with at most 6 items");
        }
        List<AgentCitation> citations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (JsonNode citation : node) {
            if (!citation.isObject()) {
                throw invalid("Each citation must be an object");
            }
            requireExactFields(citation, CITATION_FIELDS);
            int topicId = requiredPositiveInt(citation, "topicId");
            if (!knownTopicIds.contains(topicId) || !seen.add(topicId)) {
                throw invalid("Citation was not returned by an Agent tool");
            }
            String title = requiredText(citation, "title").trim();
            requireLength(title, 1, 200, "citation title");
            citations.add(new AgentCitation(topicId, title));
        }
        return List.copyOf(citations);
    }

    private void requireExactFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid("Agent output contains missing or unknown fields");
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.textValue();
    }

    private int requiredPositiveInt(JsonNode node, String field) {
        int value = requiredNonNegativeInt(node, field);
        if (value == 0) {
            throw invalid(field + " must be positive");
        }
        return value;
    }

    private int requiredNonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber() || value.intValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.intValue();
    }

    private void requireLength(String value, int min, int max, String field) {
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw invalid(field + " length is invalid");
        }
    }

    private AgentOutputValidationException invalid(String message) {
        return new AgentOutputValidationException(message);
    }
}
