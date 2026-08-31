package com.example.agent.tool;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.agent.context.TextTruncation;
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

/**
 * Agent 的 4 个声明式工具（能力边界）。
 *
 * 由 Spring AI 的 @Tool 注解反射生成 ToolCallback 供模型调用：
 * - list_topic_types ：列出论坛板块
 * - search_similar_topics：混合检索相似公开帖子
 * - read_public_topic ：读一个公开帖子的纯文本
 * - validate_draft ：校验草稿（标题/正文长度、板块存在、违禁词）
 *
 * 安全约束：
 * - 没有发布/修改工具，Agent 永远无法直接发帖
 * - 只读论坛 Mapper（TopicMapper / TopicTypeMapper），不写业务表
 * - 隐藏帖对 Agent 不可见（readPublicTopic 里过滤 invisible）
 * - 标题/正文长度限制与论坛发帖规则一致（validate_draft 是最终防线）
 */
public class ForumAuthoringTools {
    // 正文截断标记（头尾保留、中间省略）
    private static final String TRUNCATION_MARKER = "\n…[truncated]…\n";

    private final TopicTypeMapper topicTypeMapper; // 板块查询
    private final TopicMapper topicMapper; // 帖子查询（只读）
    private final HybridTopicSearchService searchService; // 混合检索
    private final ProhibitedUtils prohibitedUtils; // 违禁词检测
    private final int excerptMaxChars; // 检索摘要返回上限（上下文治理：工具出口截断）
    private final int readTopicMaxChars; // 读帖正文返回上限

    public ForumAuthoringTools(
            TopicTypeMapper topicTypeMapper,
            TopicMapper topicMapper,
            HybridTopicSearchService searchService,
            ProhibitedUtils prohibitedUtils,
            int excerptMaxChars,
            int readTopicMaxChars) {
        if (excerptMaxChars < 1 || readTopicMaxChars < 1) {
            throw new IllegalArgumentException("truncation limits must be positive");
        }
        this.topicTypeMapper = topicTypeMapper;
        this.topicMapper = topicMapper;
        this.searchService = searchService;
        this.prohibitedUtils = prohibitedUtils;
        this.excerptMaxChars = excerptMaxChars;
        this.readTopicMaxChars = readTopicMaxChars;
    }

    /**
     * 列出所有板块（过滤掉非法 id）。模型据此选择 topicTypeId。
     */
    @Tool(name = "list_topic_types", description = "List forum sections available for a new topic")
    public List<TopicTypeToolResult> listTopicTypes() {
        return topicTypeMapper.selectList(null).stream()
                .filter(type -> type.getId() != null && type.getId() > 0)
                .map(this::toToolResult)
                .toList();
    }

    /**
     * 搜索与草稿相关的历史公开帖子（混合检索：关键词 + 向量 → RRF 融合 → top6）。
     * query 非空且 ≤ 1000 字符。
     */
    @Tool(name = "search_similar_topics", description = "Search visible historical topics related to a draft")
    public List<SimilarTopicToolResult> searchSimilarTopics(
            @ToolParam(description = "Search phrase describing the planned topic") String query) {
        String normalized = requireText(query, "query", 1000);
        return searchService.search(normalized).stream()
                .map(ranked -> new SimilarTopicToolResult(
                        ranked.topic().topicId(),
                        ranked.topic().title(),
                        truncate(ranked.topic().excerpt(), excerptMaxChars),
                        ranked.topic().topicTypeId(),
                        ranked.sources(),
                        ranked.topic().topicTime()))
                .toList();
    }

    /**
     * 读取一个公开帖子的正文纯文本。
     * - topicId ≤ 0 → 参数错误
     * - 帖子不存在或 invisible=1（隐藏）→ 返回 notFound（不泄露隐藏内容）
     * - 正文从 Quill Delta JSON 里提取文本（图片不参与模型分析）
     */
    @Tool(name = "read_public_topic", description = "Read the text of one visible public topic by id")
    public PublicTopicToolResult readPublicTopic(
            @ToolParam(description = "Positive forum topic id") int topicId) {
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
                truncateHeadAndTail(extractText(topic.getContent()), readTopicMaxChars));
    }

    /**
     * 校验草稿是否符合发帖规则：
     * - 标题 1-30 字、正文 1-20000 字（按 Unicode 码点计数）
     * - 板块必须存在
     * - 标题/正文不含违禁词
     * 这是 Agent 产出的最终防线——DRAFT 必须通过它才能返回。
     */
    @Tool(name = "validate_draft", description = "Validate a proposed draft against forum publishing constraints")
    public DraftValidationToolResult validateDraft(
            @ToolParam(description = "Proposed topic title") String title,
            @ToolParam(description = "Proposed forum section id") int topicTypeId,
            @ToolParam(description = "Proposed Markdown body text") String bodyMarkdown) {
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

    /** 板块实体 → 工具结果 DTO。 */
    private TopicTypeToolResult toToolResult(TopicType type) {
        return new TopicTypeToolResult(type.getId(), type.getName(), type.getDesc());
    }

    /**
     * 从 Quill Delta JSON 提取纯文本：
     * content 形如 {"ops":[{"insert":"文字"},{"insert":{"image":"..."}}]}，
     * 只拼接字符串 insert，图片等对象忽略；解析失败返回空串。
     */
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

    /** 参数必填 + 长度校验（工具参数，非法则抛 IllegalArgumentException 拒绝调用）。 */
    private String requireText(String value, String field, int maxLength) {
        if (!hasLength(value, 1, maxLength)) {
            throw new IllegalArgumentException(field + " length is invalid");
        }
        return value.trim();
    }

    /** 尾部截断：超长保留头部并以省略号结尾。用于检索摘要——模型只需判断相关性。 */
    private String truncate(String text, int maxChars) {
        return TextTruncation.truncateHead(text, maxChars, "…");
    }

    /** 头尾保留截断。用于读帖正文——帖子的开头与结尾往往都有信息量。 */
    private String truncateHeadAndTail(String text, int maxChars) {
        return TextTruncation.truncateHeadAndTail(text, maxChars, TRUNCATION_MARKER);
    }

    /** 长度校验：按 Unicode 码点计数（正确统计中文/emoji），null 视为不合法。 */
    private boolean hasLength(String value, int min, int max) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        int length = normalized.codePointCount(0, normalized.length());
        return length >= min && length <= max;
    }
}
