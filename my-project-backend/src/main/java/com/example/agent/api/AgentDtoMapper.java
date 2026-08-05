package com.example.agent.api;

import com.example.agent.core.AgentCitation;
import com.example.agent.run.AgentSseEventType;
import com.example.agent.run.CitationPayload;
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

final class AgentDtoMapper {
    private static final TypeReference<List<AgentCitation>> CITATIONS_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    AgentDtoMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AgentApiDtos.SessionSummary summary(AgentSession session) {
        return new AgentApiDtos.SessionSummary(
                session.getId(),
                session.getStatus(),
                instant(session.getCreatedAt()),
                instant(session.getUpdatedAt()),
                instant(session.getExpiresAt())
        );
    }

    AgentApiDtos.SessionDetail detail(AgentSessionAggregate aggregate) {
        return new AgentApiDtos.SessionDetail(
                summary(aggregate.session()),
                aggregate.messages().stream().map(this::message).toList(),
                aggregate.events().stream().map(this::event).toList(),
                aggregate.draft() == null ? null : draft(aggregate.draft())
        );
    }

    private AgentApiDtos.Message message(AgentMessage message) {
        return new AgentApiDtos.Message(
                message.getId(), message.getRole(), message.getContent(), instant(message.getCreatedAt())
        );
    }

    private AgentApiDtos.Event event(AgentEvent event) {
        AgentSseEventType type = AgentSseEventType.fromWireName(event.getType());
        return new AgentApiDtos.Event(
                event.getId(),
                event.getRunId(),
                event.getSequenceNo(),
                type.wireName(),
                read(event.getPayloadJson(), payloadType(type)),
                instant(event.getCreatedAt())
        );
    }

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
                instant(draft.getUpdatedAt())
        );
    }

    private Class<?> payloadType(AgentSseEventType type) {
        return switch (type) {
            case RUN_STARTED -> RunStartedPayload.class;
            case MESSAGE_DELTA -> MessageDeltaPayload.class;
            case TOOL_STARTED, TOOL_COMPLETED -> ToolEventPayload.class;
            case CITATION -> CitationPayload.class;
            case QUESTION -> QuestionPayload.class;
            case DRAFT_READY -> DraftReadyPayload.class;
            case RUN_COMPLETED -> RunCompletedPayload.class;
            case ERROR -> ErrorPayload.class;
        };
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Agent data is invalid", exception);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored Agent data is invalid", exception);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
