package com.example.agent.api;

import com.example.agent.core.AgentCitation;
import com.example.agent.run.AgentSseEventType;
import com.example.agent.run.AnswerPayload;
import com.example.agent.run.CitationPayload;
import com.example.agent.run.ContextNoticePayload;
import com.example.agent.run.DraftReadyPayload;
import com.example.agent.run.ErrorPayload;
import com.example.agent.run.MessageDeltaPayload;
import com.example.agent.run.QuestionPayload;
import com.example.agent.run.RunCompletedPayload;
import com.example.agent.run.RunStartedPayload;
import com.example.agent.run.ToolEventPayload;
import com.example.agent.session.AgentDraft;
import com.example.agent.session.AgentEvent;
import com.example.agent.session.AgentMessage;
import com.example.agent.session.AgentSession;
import com.example.agent.session.AgentSessionAggregate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 领域对象（session 包实体）↔ API DTO（AgentApiDtos）的转换器。
 *
 * 职责：把数据库实体 / 聚合根转成前端友好的 DTO 结构，
 * 同时把事件 payload 的 JSON 字符串反序列化成具体的 Payload 对象（供前端直接使用）。
 * 包级私有：只被 {@link AgentController} 使用，不对外暴露。
 */
final class AgentDtoMapper {
    // 泛型类型引用：用于把草稿里的 citations JSON 反序列化成 List<AgentCitation>
    private static final TypeReference<List<AgentCitation>> CITATIONS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    AgentDtoMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 会话实体 → 会话摘要 DTO。
     * title 单独传入：由 SessionService 根据消息内容动态解析，实体里不存标题。
     */
    AgentApiDtos.SessionSummary summary(AgentSession session, String title) {
        return new AgentApiDtos.SessionSummary(
                session.getId(),
                session.getStatus(),
                title,
                instant(session.getCreatedAt()),
                instant(session.getUpdatedAt()),
                instant(session.getExpiresAt()));
    }

    /**
     * 聚合根 → 会话详情 DTO：组装摘要 + 全部消息 + 全部事件 + 最新草稿。
     */
    AgentApiDtos.SessionDetail detail(AgentSessionAggregate aggregate, String title) {
        return new AgentApiDtos.SessionDetail(
                summary(aggregate.session(), title),
                aggregate.messages().stream().map(this::message).toList(),
                aggregate.events().stream().map(this::event).toList(),
                aggregate.draft() == null ? null : draft(aggregate.draft()));
    }

    /** 单条消息实体 → DTO。 */
    private AgentApiDtos.Message message(AgentMessage message) {
        return new AgentApiDtos.Message(
                message.getId(), message.getRole(), message.getContent(), instant(message.getCreatedAt()));
    }

    /**
     * 单条事件实体 → DTO。
     * 关键步骤：根据事件类型（wireName）找到对应的 Payload 类，
     * 把存储的 JSON 字符串反序列化成结构化对象。
     */
    private AgentApiDtos.Event event(AgentEvent event) {
        AgentSseEventType type = AgentSseEventType.fromWireName(event.getType());
        return new AgentApiDtos.Event(
                event.getId(),
                event.getRunId(),
                event.getSequenceNo(),
                type.wireName(),
                read(event.getPayloadJson(), payloadType(type)),
                instant(event.getCreatedAt()));
    }

    /**
     * 草稿实体 → DTO：citations 是 JSON 字符串，这里反序列化成对象列表。
     */
    private AgentApiDtos.Draft draft(AgentDraft draft) {
        List<AgentCitation> citations = draft.getCitationsJson() == null
                ? List.of()
                : read(draft.getCitationsJson(), CITATIONS_TYPE);
        return new AgentApiDtos.Draft(
                draft.getVersion(),
                draft.getEditorVersion(),
                draft.getTargetEditorId(),
                draft.getTitle(),
                draft.getTopicTypeId(),
                draft.getBodyMarkdown(),
                citations,
                instant(draft.getCreatedAt()),
                instant(draft.getUpdatedAt()));
    }

    /**
     * 事件类型 → 对应的 Payload 类（用于反序列化）。
     * TOOL_STARTED 和 TOOL_COMPLETED 共用 ToolEventPayload。
     */
    private Class<?> payloadType(AgentSseEventType type) {
        return switch (type) {
            case RUN_STARTED -> RunStartedPayload.class;
            case MESSAGE_DELTA -> MessageDeltaPayload.class;
            case TOOL_STARTED, TOOL_COMPLETED -> ToolEventPayload.class;
            case CITATION -> CitationPayload.class;
            case QUESTION -> QuestionPayload.class;
            case ANSWER -> AnswerPayload.class;
            case DRAFT_READY -> DraftReadyPayload.class;
            case RUN_COMPLETED -> RunCompletedPayload.class;
            case CONTEXT_NOTICE -> ContextNoticePayload.class;
            case ERROR -> ErrorPayload.class;
        };
    }

    /** 按 Class 反序列化 JSON（用于事件 payload）。 */
    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            // 数据库里的事件数据损坏时抛出 IllegalStateException，由兜底异常处理器转 500
            throw new IllegalStateException("Stored Agent data is invalid", exception);
        }
    }

    /** 按 TypeReference 反序列化 JSON（用于泛型 List<AgentCitation>）。 */
    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Agent data is invalid", exception);
        }
    }

    /** 数据库 Timestamp → Java Instant（统一时间类型）。 */
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
