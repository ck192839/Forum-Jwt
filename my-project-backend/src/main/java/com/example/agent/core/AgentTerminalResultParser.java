package com.example.agent.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 终端结果解析器：把模型输出的原始 JSON 严格解析成 {@link AgentTerminalResult}。
 *
 * 设计核心是「严格白名单」：\n
 * - QUESTION 只允许 {type, question} 两个字段，多一个少一个都拒绝\n
 * - DRAFT 只允许 {type, title, topicTypeId, bodyMarkdown, citations,
 * basedOnEditorVersion}\n
 * - 引用 topicId 必须出现在 knownTopicIds（工具真实返回）里，且不重复、最多 6 条\n
 * - basedOnEditorVersion 必须等于当前编辑器版本（防过期草稿）\n
 * \n
 * 所有校验失败都抛 {@link AgentOutputValidationException}（可修复，模型可重试），
 * 由 ForumReActAgent 捕获后发起修复提示。
 */
public class AgentTerminalResultParser {
    // QUESTION 终态的精确字段集合
    private static final Set<String> QUESTION_FIELDS = Set.of("type", "question");
    // DRAFT 终态的精确字段集合
    private static final Set<String> DRAFT_FIELDS = Set.of(
            "type", "title", "topicTypeId", "bodyMarkdown", "citations", "basedOnEditorVersion");
    // 单条引用的精确字段集合
    private static final Set<String> CITATION_FIELDS = Set.of("topicId", "title");

    private final ObjectMapper objectMapper;

    public AgentTerminalResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析入口。
     *
     * @param raw           模型输出的原始文本（应为 JSON 对象，不带 Markdown 围栏）
     * @param editorVersion 当前编辑器版本（用于 DRAFT 的过期校验）
     * @param knownTopicIds 工具真实返回过的 topicId 集合（引用白名单）
     */
    public AgentTerminalResult parse(String raw, int editorVersion, Set<Integer> knownTopicIds) {
        // 粗筛：必须是 { 开头 } 结尾（挡住 Markdown 围栏 / 前后废话）
        if (raw == null || !raw.trim().startsWith("{") || !raw.trim().endsWith("}")) {
            throw invalid("Agent output must be a JSON object without code fences");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException exception) {
            throw new AgentOutputValidationException("Agent output is not valid JSON", exception);
        }
        // 必须是 JSON 对象（不是数组/字符串/数字）
        if (!root.isObject()) {
            throw invalid("Agent output must be a JSON object");
        }
        // 按 type 字段分流：QUESTION / DRAFT
        String type = requiredText(root, "type");
        return switch (type) {
            case "QUESTION" -> parseQuestion(root);
            case "DRAFT" -> parseDraft(root, editorVersion, knownTopicIds);
            default -> throw invalid("Unsupported Agent result type: " + type);
        };
    }

    /** 解析 QUESTION 终态：精确字段 + 问题文本长度 1-1000。 */
    private AgentQuestionResult parseQuestion(JsonNode root) {
        requireExactFields(root, QUESTION_FIELDS);
        String question = requiredText(root, "question").trim();
        requireLength(question, 1, 1000, "question");
        return new AgentQuestionResult(question);
    }

    /**
     * 解析 DRAFT 终态：
     * - 精确字段集合\n
     * - 标题 1-30 字、正文 1-20000 字、板块 id 为正整数\n
     * - 编辑器版本必须等于当前版本\n
     * - 引用必须全部来自工具白名单
     */
    private AgentDraftResult parseDraft(JsonNode root, int editorVersion, Set<Integer> knownTopicIds) {
        requireExactFields(root, DRAFT_FIELDS);
        String title = requiredText(root, "title").trim();
        String body = requiredText(root, "bodyMarkdown").trim();
        requireLength(title, 1, 30, "title");
        requireLength(body, 1, 20_000, "bodyMarkdown");
        int topicTypeId = requiredPositiveInt(root, "topicTypeId");
        int basedOnEditorVersion = requiredNonNegativeInt(root, "basedOnEditorVersion");
        // 版本一致性：草稿基于的版本必须和发起运行时的版本一致，防止过期覆盖
        if (basedOnEditorVersion != editorVersion) {
            throw invalid("Draft is based on a stale editor version");
        }
        List<AgentCitation> citations = parseCitations(root.get("citations"), knownTopicIds);
        return new AgentDraftResult(title, topicTypeId, body, citations, basedOnEditorVersion);
    }

    /**
     * 解析引用数组：最多 6 条、每条精确两字段、topicId 必须在白名单且不重复。
     */
    private List<AgentCitation> parseCitations(JsonNode node, Set<Integer> knownTopicIds) {
        if (node == null || !node.isArray() || node.size() > 6) {
            throw invalid("citations must be an array with at most 6 items");
        }
        List<AgentCitation> citations = new ArrayList<>();
        Set<Integer> seen = new HashSet<>(); // 去重
        for (JsonNode citation : node) {
            if (!citation.isObject()) {
                throw invalid("Each citation must be an object");
            }
            requireExactFields(citation, CITATION_FIELDS);
            int topicId = requiredPositiveInt(citation, "topicId");
            // 关键安全校验：引用必须来自工具真实返回，且本条没出现过
            if (!knownTopicIds.contains(topicId) || !seen.add(topicId)) {
                throw invalid("Citation was not returned by an Agent tool");
            }
            String title = requiredText(citation, "title").trim();
            requireLength(title, 1, 200, "citation title");
            citations.add(new AgentCitation(topicId, title));
        }
        return List.copyOf(citations);
    }

    /** 精确字段校验：对象字段集合必须与 expected 完全相等（防止模型夹带私货字段）。 */
    private void requireExactFields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid("Agent output contains missing or unknown fields");
        }
    }

    /** 必填字符串字段：必须存在且为字符串。 */
    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.textValue();
    }

    /** 必填正整数：先过非负校验，再排除 0。 */
    private int requiredPositiveInt(JsonNode node, String field) {
        int value = requiredNonNegativeInt(node, field);
        if (value == 0) {
            throw invalid(field + " must be positive");
        }
        return value;
    }

    /** 必填非负整数：必须能转 int、是整数、且 ≥ 0。 */
    private int requiredNonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || !value.isIntegralNumber() || value.intValue() < 0) {
            throw invalid(field + " must be a non-negative integer");
        }
        return value.intValue();
    }

    /** 长度校验：用 codePointCount（按 Unicode 码点计数，正确处理中文/emoji）。 */
    private void requireLength(String value, int min, int max, String field) {
        int length = value.codePointCount(0, value.length());
        if (length < min || length > max) {
            throw invalid(field + " length is invalid");
        }
    }

    /** 统一构造「可修复」校验异常。 */
    private AgentOutputValidationException invalid(String message) {
        return new AgentOutputValidationException(message);
    }
}
