package com.example.agent.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.agent.run.AgentEventSink;
import com.example.agent.run.AgentSseEventType;
import com.example.agent.run.AgentRunCommand;
import com.example.agent.run.AgentRunConflictException;
import com.example.agent.run.AgentRunService;
import com.example.agent.run.RunStartedPayload;
import com.example.agent.session.AgentDraft;
import com.example.agent.session.AgentEvent;
import com.example.agent.session.AgentMessage;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSession;
import com.example.agent.session.AgentSessionAggregate;
import com.example.agent.session.AgentSessionNotFoundException;
import com.example.agent.session.AgentSessionService;
import com.example.agent.session.AgentSessionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerTest {
    private AgentSessionService sessions;
    private AgentRunService runs;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sessions = mock(AgentSessionService.class);
        runs = mock(AgentRunService.class);
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AgentController(sessions, runs, new ObjectMapper(), 30_000L))
                .setControllerAdvice(new AgentControllerAdvice())
                .build();
    }

    @Test
    void createsAndListsSessionsForCurrentUserOnly() throws Exception {
        AgentSession created = session(99L, 7);
        when(sessions.create(7)).thenReturn(created);
        when(sessions.listRecent(7)).thenReturn(List.of(created));

        mvc.perform(post("/api/agent/sessions").requestAttr("userId", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(99));
        mvc.perform(get("/api/agent/sessions/recent").requestAttr("userId", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(99));

        verify(sessions).create(7);
        verify(sessions).listRecent(7);
    }

    @Test
    void restoresOwnedMessagesTypedToolEventsAndVersionedDraft() throws Exception {
        AgentSession session = session(99L, 7);
        AgentMessage message = new AgentMessage();
        message.setId(11L);
        message.setSessionId(99L);
        message.setRole(AgentMessageRole.USER);
        message.setContent("write a guide");
        message.setCreatedAt(Timestamp.from(Instant.parse("2026-08-05T01:00:00Z")));
        AgentEvent event = new AgentEvent();
        event.setId(12L);
        event.setSessionId(99L);
        event.setRunId("run-1");
        event.setSequenceNo(2);
        event.setType("tool_started");
        event.setPayloadJson("{\"runId\":\"run-1\",\"toolName\":\"search_similar_topics\"}");
        event.setCreatedAt(Timestamp.from(Instant.parse("2026-08-05T01:00:01Z")));
        AgentDraft draft = new AgentDraft();
        draft.setSessionId(99L);
        draft.setVersion(4);
        draft.setEditorVersion(9);
        draft.setTitle("Guide");
        draft.setTopicTypeId(3);
        draft.setBodyMarkdown("Body");
        draft.setCitationsJson("[{\"topicId\":42,\"title\":\"Source\"}]");
        when(sessions.load(7, 99L)).thenReturn(new AgentSessionAggregate(
                session, List.of(message), List.of(event), draft
        ));

        mvc.perform(get("/api/agent/sessions/99").requestAttr("userId", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.data.events[0].type").value("tool_started"))
                .andExpect(jsonPath("$.data.events[0].payload.toolName").value("search_similar_topics"))
                .andExpect(jsonPath("$.data.draft.version").value(4))
                .andExpect(jsonPath("$.data.draft.editorVersion").value(9))
                .andExpect(jsonPath("$.data.draft.citations[0].topicId").value(42));

        verify(sessions).load(7, 99L);
    }

    @Test
    void mapsForeignSessionToNotFoundAndNeverAllowsAdminStyleBypass() throws Exception {
        when(sessions.load(7, 99L)).thenThrow(new AgentSessionNotFoundException());

        mvc.perform(get("/api/agent/sessions/99").requestAttr("userId", 7))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));

        verify(sessions).load(7, 99L);
    }

    @Test
    void deletesSessionThroughOwnerScopedService() throws Exception {
        mvc.perform(delete("/api/agent/sessions/99").requestAttr("userId", 7))
                .andExpect(status().isOk());

        verify(sessions).delete(7, 99L);
    }

    @Test
    void rejectsEmptyRunRequestAndInvalidEditorVersion() throws Exception {
        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"editorVersion\":-1}"))
                .andExpect(status().isBadRequest());

        verify(runs, never()).start(any(Integer.class), any(Long.class), any(), any());
    }

    @Test
    void rejectsBlankMessageWithEmptyEditorDraft() throws Exception {
        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"  \",\"editorVersion\":2,\"editorDraft\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsEditorDraftContainingOnlyBlankText() throws Exception {
        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"editorVersion\":2,\"editorDraft\":{\"title\":\" \","
                                + "\"bodyMarkdown\":\"  \"}}"))
                .andExpect(status().isBadRequest());

        verify(runs, never()).start(any(Integer.class), any(Long.class), any(), any());
    }

    @Test
    void rejectsOversizedAgentInputsAndNonPositiveTopicType() throws Exception {
        List<String> invalidBodies = List.of(
                "{\"message\":\"" + "m".repeat(8_001) + "\",\"editorVersion\":1}",
                "{\"editorVersion\":1,\"editorDraft\":{\"title\":\"" + "t".repeat(31)
                        + "\",\"topicTypeId\":1}}",
                "{\"editorVersion\":1,\"editorDraft\":{\"bodyMarkdown\":\"" + "b".repeat(20_001)
                        + "\",\"topicTypeId\":1}}",
                "{\"editorVersion\":1,\"editorDraft\":{\"topicTypeId\":-1}}"
        );

        for (String body : invalidBodies) {
            mvc.perform(post("/api/agent/sessions/99/runs")
                            .requestAttr("userId", 7)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        verify(runs, never()).start(any(Integer.class), any(Long.class), any(), any());
    }

    @Test
    void acceptsAgentInputAtEveryDocumentedBoundary() throws Exception {
        when(runs.start(eq(7), eq(99L), any(), any())).thenReturn("run-boundary");
        String body = "{\"message\":\"" + "m".repeat(8_000)
                + "\",\"editorVersion\":0,\"editorDraft\":{\"title\":\"" + "t".repeat(30)
                + "\",\"topicTypeId\":1,\"bodyMarkdown\":\"" + "b".repeat(20_000) + "\"}}";

        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(runs).start(eq(7), eq(99L), any(), any());
    }

    @Test
    void treatsUnknownPersistedEventTypeAsLoggedInternalError() throws Exception {
        AgentEvent event = new AgentEvent();
        event.setId(12L);
        event.setSessionId(99L);
        event.setRunId("run-1");
        event.setSequenceNo(1);
        event.setType("future_event");
        event.setPayloadJson("{\"future\":true}");
        when(sessions.load(7, 99L)).thenReturn(new AgentSessionAggregate(
                session(99L, 7), List.of(), List.of(event), null
        ));
        Logger logger = (Logger) LoggerFactory.getLogger(AgentControllerAdvice.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mvc.perform(get("/api/agent/sessions/99").requestAttr("userId", 7))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("Unable to start or restore Agent operation"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, appender.list.size());
        assertEquals(Level.ERROR, appender.list.get(0).getLevel());
        assertEquals("Agent API operation failed", appender.list.get(0).getFormattedMessage());
    }

    @Test
    void startsSseRunWithoutWaitingForAgentCompletion() throws Exception {
        when(runs.start(eq(7), eq(99L), any(), any())).thenAnswer(invocation -> {
            AgentEventSink sink = invocation.getArgument(3);
            sink.emit(AgentSseEventType.RUN_STARTED, "run-1:1", new RunStartedPayload("run-1", 99L));
            return "run-1";
        });

        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"Improve it\",\"editorVersion\":8,"
                                + "\"editorDraft\":{\"title\":\"Old\",\"topicTypeId\":2,"
                                + "\"bodyMarkdown\":\"Body\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted());

        ArgumentCaptor<AgentRunCommand> command = ArgumentCaptor.forClass(AgentRunCommand.class);
        verify(runs).start(eq(7), eq(99L), command.capture(), any(AgentEventSink.class));
        assertEquals("Improve it", command.getValue().message());
        assertEquals(8, command.getValue().editorVersion());
        assertEquals("Old", command.getValue().editorTitle());
    }

    @Test
    void startsRunFromEditorDraftWithoutMessage() throws Exception {
        when(runs.start(eq(7), eq(99L), any(), any())).thenReturn("run-2");

        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"editorVersion\":3,\"editorDraft\":{\"bodyMarkdown\":\"Draft\"}}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        ArgumentCaptor<AgentRunCommand> command = ArgumentCaptor.forClass(AgentRunCommand.class);
        verify(runs).start(eq(7), eq(99L), command.capture(), any(AgentEventSink.class));
        assertEquals(null, command.getValue().message());
        assertEquals("Draft", command.getValue().editorBodyMarkdown());
    }

    @Test
    void mapsActiveRunConflictToHttp409() throws Exception {
        when(runs.start(eq(7), eq(99L), any(), any())).thenThrow(new AgentRunConflictException());

        mvc.perform(post("/api/agent/sessions/99/runs")
                        .requestAttr("userId", 7)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"editorVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
    }

    @Test
    void cancellationSucceedsOnlyWhenRunServiceConfirmsOwnership() throws Exception {
        when(runs.cancel(7, "mine")).thenReturn(true);
        when(runs.cancel(7, "foreign")).thenReturn(false);

        mvc.perform(delete("/api/agent/runs/mine").requestAttr("userId", 7))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/agent/runs/foreign").requestAttr("userId", 7))
                .andExpect(status().isNotFound());
    }

    private static AgentSession session(long id, int uid) {
        AgentSession session = new AgentSession();
        session.setId(id);
        session.setUid(uid);
        session.setStatus(AgentSessionStatus.ACTIVE);
        session.setCreatedAt(Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")));
        session.setUpdatedAt(Timestamp.from(Instant.parse("2026-08-05T00:00:00Z")));
        session.setExpiresAt(Timestamp.from(Instant.parse("2026-09-04T00:00:00Z")));
        return session;
    }
}
