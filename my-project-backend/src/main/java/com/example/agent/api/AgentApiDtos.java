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

public final class AgentApiDtos {
    private AgentApiDtos() {
    }

    public record SessionSummary(
            long id,
            AgentSessionStatus status,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt
    ) {
    }

    public record SessionDetail(
            SessionSummary session,
            List<Message> messages,
            List<Event> events,
            Draft draft
    ) {
        public SessionDetail {
            messages = List.copyOf(messages);
            events = List.copyOf(events);
        }
    }

    public record Message(
            long id,
            AgentMessageRole role,
            String content,
            Instant createdAt
    ) {
    }

    public record Event(
            long id,
            String runId,
            int sequence,
            String type,
            Object payload,
            Instant createdAt
    ) {
    }

    public record Draft(
            int version,
            int editorVersion,
            String targetEditorId,
            String title,
            Integer topicTypeId,
            String bodyMarkdown,
            List<AgentCitation> citations,
            Instant createdAt,
            Instant updatedAt
    ) {
        public Draft {
            citations = List.copyOf(citations);
        }
    }

    public record EditorDraft(
            @Size(max = 30) String title,
            @Positive Integer topicTypeId,
            @Size(max = 20_000) String bodyMarkdown
    ) {
        @JsonIgnore
        boolean hasContent() {
            return (title != null && !title.isBlank())
                    || topicTypeId != null
                    || (bodyMarkdown != null && !bodyMarkdown.isBlank());
        }
    }

    public record RunRequest(
            @Size(max = 8_000) String message,
            @Size(max = 128) String editorId,
            @NotNull @Min(0) Integer editorVersion,
            @Valid EditorDraft editorDraft
    ) {
        @AssertTrue(message = "A message or editor draft is required")
        @JsonIgnore
        public boolean isContentPresent() {
            return (message != null && !message.isBlank())
                    || (editorDraft != null && editorDraft.hasContent());
        }
    }
}
