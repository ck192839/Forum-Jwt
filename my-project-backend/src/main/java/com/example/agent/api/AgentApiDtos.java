package com.example.agent.api;

import com.example.agent.core.AgentCitation;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSessionStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Agent API 层的 DTO 定义（对外契约）。
 *
 * 全部用 Java record 表达，只承载数据传输、不做业务逻辑。
 * 分层原则：DTO（本类）↔ 领域对象（session 包）↔ 数据库实体（session 包 Mapper）
 * 通过 {@link AgentDtoMapper} 互相转换，避免数据库实体直接暴露给前端。
 *
 * 命名约定：
 * - Session* : 会话相关（列表 / 详情）
 * - Message : 会话里的聊天消息（USER / ASSISTANT）
 * - Event : 已落库的 SSE 事件（可重放恢复 UI）
 * - Draft : Agent 生成的草稿
 * - RunRequest: 发起一次 Agent 运行的请求体
 */
public final class AgentApiDtos {
    // 工具类：禁止实例化（所有 DTO 都是嵌套的静态 record，不需要本类的实例）
    private AgentApiDtos() {
    }

    /**
     * 会话摘要：用于「最近会话列表」和「会话详情」的头部。
     * expiresAt 为过期时间，过期会话会被
     * {@link com.example.agent.session.AgentSessionCleanupJob} 清理。
     */
    public record SessionSummary(
            long id,
            AgentSessionStatus status,
            String title,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt) {
    }

    /**
     * 会话详情：恢复会话时前端需要的全部数据。
     * - messages：完整聊天记录（用于渲染消息列表）
     * - events ：历史 SSE 事件（前端重放事件来重建工具时间线、引用等 UI 状态）
     * - draft ：最新草稿（可能为 null）
     * 构造时用 List.copyOf 防御性拷贝，防止外部修改内部列表。
     */
    public record SessionDetail(
            SessionSummary session,
            List<Message> messages,
            List<Event> events,
            Draft draft) {
        public SessionDetail {
            messages = List.copyOf(messages);
            events = List.copyOf(events);
        }
    }

    /**
     * 一条聊天消息。role 区分用户输入与助手输出。
     * 注意：助手侧的终态 JSON（DRAFT/QUESTION）不直接展示，由前端过滤。
     */
    public record Message(
            long id,
            AgentMessageRole role,
            String content,
            Instant createdAt) {
    }

    /**
     * 一条已持久化的 SSE 事件。
     * - runId + sequence：全局唯一序号（eventId = runId + ":" + sequence），保证事件顺序
     * - type ：事件类型（wireName，如 run_started / draft_ready）
     * - payload：事件负载，反序列化为对应类型的 Payload 对象
     */
    public record Event(
            long id,
            String runId,
            int sequence,
            String type,
            Object payload,
            Instant createdAt) {
    }

    /**
     * Agent 生成的草稿（恢复到编辑器用）。
     * - version ：草稿自身的版本号（同会话每次生成递增）
     * - editorVersion ：草稿基于的编辑器版本号（用于防过期覆盖）
     * - targetEditorId ：草稿应被应用到的编辑器 id（null 表示新建帖）
     * - citations ：引用帖子列表
     */
    public record Draft(
            int version,
            int editorVersion,
            String targetEditorId,
            String title,
            Integer topicTypeId,
            String bodyMarkdown,
            List<AgentCitation> citations,
            Instant createdAt,
            Instant updatedAt) {
        public Draft {
            citations = List.copyOf(citations);
        }
    }

    /**
     * 「编辑器里的现有内容」——用户从编辑器发起 AI 优化时携带的上下文。
     * 字段均有长度/正数校验：
     * - title ≤ 30 字
     * - topicTypeId > 0
     * - bodyMarkdown ≤ 20000 字
     * hasContent()：判断用户是否至少提供了一项内容（@JsonIgnore 表示不参与序列化，仅供校验用）。
     */
    public record EditorDraft(
            @Size(max = 30) String title,
            @Positive Integer topicTypeId,
            @Size(max = 20_000) String bodyMarkdown) {
        @JsonIgnore
        boolean hasContent() {
            return (title != null && !title.isBlank())
                    || topicTypeId != null
                    || (bodyMarkdown != null && !bodyMarkdown.isBlank());
        }
    }

    /**
     * 发起一次 Agent 运行的请求体。
     * - message ：用户输入的自然语言（≤ 8000 字）
     * - editorId ：编辑器稳定 id（如 topic-editor:new-topic），可为 null（纯聊天）
     * - editorVersion ：当前编辑器版本号（防过期草稿，必填且 ≥ 0）
     * - editorDraft ：编辑器现有内容（AI 优化场景），可为 null
     * isContentPresent()：@AssertTrue 类级校验——message 和 editorDraft 至少得有一样，否则 400。
     */
    public record RunRequest(
            @Size(max = 8_000) String message,
            @Size(max = 128) String editorId,
            @NotNull @Min(0) Integer editorVersion,
            @Valid EditorDraft editorDraft) {
        @AssertTrue(message = "A message or editor draft is required")
        @JsonIgnore
        public boolean isContentPresent() {
            return (message != null && !message.isBlank())
                    || (editorDraft != null && editorDraft.hasContent());
        }
    }
}
