package com.example.agent.run;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.agent.core.AgentAnswerResult;
import com.example.agent.core.AgentCitation;
import com.example.agent.core.AgentDraftResult;
import com.example.agent.core.AgentQuestionResult;
import com.example.agent.core.AgentRunContext;
import com.example.agent.core.AgentRunException;
import com.example.agent.core.AgentRunFailure;
import com.example.agent.core.AgentRunRequest;
import com.example.agent.core.AgentRunner;
import com.example.agent.session.AgentDraft;
import com.example.agent.session.AgentDraftInput;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSession;
import com.example.agent.session.AgentSessionAggregate;
import com.example.agent.session.AgentSessionService;
import com.example.entity.vo.response.WeatherVO;
import com.example.service.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunServiceTest {

    @Test
    void emitsAndPersistsTypedQuestionRunEvents() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        AgentRunner runner = (request, cancellation, observer) -> {
            observer.toolStarted("list_topic_types", "{}");
            observer.toolCompleted("list_topic_types", "[]");
            return new AgentQuestionResult("Which audience should this target?");
        };
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, Runnable::run);

        String runId = service.start(
                7,
                99L,
                new AgentRunCommand("Help me write", 4, null, null, null, "topic-editor-question"),
                sink);

        assertEquals("run-1", runId);
        assertEquals(List.of(
                AgentSseEventType.RUN_STARTED,
                AgentSseEventType.TOOL_STARTED,
                AgentSseEventType.TOOL_COMPLETED,
                AgentSseEventType.QUESTION,
                AgentSseEventType.RUN_COMPLETED), sink.types());
        assertEquals(List.of("run-1:1", "run-1:2", "run-1:3", "run-1:4", "run-1:5"), sink.ids());
        assertTrue(sink.completed);
        QuestionPayload question = (QuestionPayload) sink.payloads.get(3);
        assertEquals("topic-editor-question", question.targetEditorId());
        assertEquals(4, question.basedOnEditorVersion());
        verify(sessions).appendMessage(7, 99L, AgentMessageRole.USER, "Help me write");
        verify(sessions).appendMessage(7, 99L, AgentMessageRole.ASSISTANT, "Which audience should this target?");
    }

    @Test
    void persistsVersionedDraftAndEmitsCitationsWithoutPublishing() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        AgentRunner runner = (request, cancellation, observer) -> new AgentDraftResult(
                "Network guide",
                3,
                "Try these steps.",
                List.of(new AgentCitation(42, "Previous guide")),
                8);
        AgentDraft persisted = new AgentDraft();
        persisted.setVersion(5);
        persisted.setEditorVersion(8);
        persisted.setTargetEditorId("topic-editor-8");
        when(sessions.saveDraft(anyInt(), any(Long.class), any(AgentDraftInput.class))).thenReturn(persisted);
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, Runnable::run);

        service.start(
                7,
                99L,
                new AgentRunCommand("Improve this", 8, "Old title", 2, "Old body", "topic-editor-8"),
                sink);

        ArgumentCaptor<AgentDraftInput> input = ArgumentCaptor.forClass(AgentDraftInput.class);
        verify(sessions).saveDraft(org.mockito.ArgumentMatchers.eq(7), org.mockito.ArgumentMatchers.eq(99L),
                input.capture());
        assertEquals(8, input.getValue().editorVersion());
        assertEquals("topic-editor-8", input.getValue().targetEditorId());
        assertEquals("Network guide", input.getValue().title());
        assertTrue(input.getValue().citationsJson().contains("42"));
        assertEquals(List.of(
                AgentSseEventType.RUN_STARTED,
                AgentSseEventType.CITATION,
                AgentSseEventType.DRAFT_READY,
                AgentSseEventType.RUN_COMPLETED), sink.types());
        assertEquals(5, ((DraftReadyPayload) sink.payloads.get(2)).draftVersion());
        assertEquals("topic-editor-8", ((DraftReadyPayload) sink.payloads.get(2)).targetEditorId());
    }

    @Test
    void rejectsConcurrentRunForTheSameSession() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        QueueExecutor executor = new QueueExecutor();
        AgentRunService service = service(
                sessions,
                (request, cancellation, observer) -> new AgentQuestionResult("Question"),
                executor);

        service.start(7, 99L, new AgentRunCommand("First", 1, null, null, null), new RecordingSink());

        assertThrows(AgentRunConflictException.class, () -> service.start(
                7,
                99L,
                new AgentRunCommand("Second", 1, null, null, null),
                new RecordingSink()));
        executor.runNext();
        assertFalse(service.hasActiveRun(99L));
    }

    @Test
    void cancelsOnlyTheOwnersActiveRun() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        QueueExecutor executor = new QueueExecutor();
        AgentRunner runner = (request, cancellation, observer) -> {
            if (cancellation.isCancelled()) {
                throw new AgentRunException(AgentRunFailure.CANCELLED, "cancelled");
            }
            return new AgentQuestionResult("late");
        };
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, executor);
        String runId = service.start(
                7,
                99L,
                new AgentRunCommand("First", 1, null, null, null),
                sink);

        assertFalse(service.cancel(8, runId));
        assertTrue(service.cancel(7, runId));
        executor.runNext();

        assertEquals(List.of(
                AgentSseEventType.RUN_STARTED,
                AgentSseEventType.ERROR,
                AgentSseEventType.RUN_COMPLETED), sink.types());
        ErrorPayload error = (ErrorPayload) sink.payloads.get(1);
        assertEquals("CANCELLED", error.code());
        assertTrue(error.retryable());
        assertFalse(service.hasActiveRun(99L));
    }

    @Test
    void modelFailureNeverPersistsADraftAndAllowsRetry() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        AgentRunner runner = (request, cancellation, observer) -> {
            throw new AgentRunException(AgentRunFailure.EXECUTION, "model failed");
        };
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, Runnable::run);

        service.start(7, 99L, new AgentRunCommand("Retry me", 1, null, null, null), sink);

        verify(sessions, never()).saveDraft(anyInt(), any(Long.class), any(AgentDraftInput.class));
        ErrorPayload error = (ErrorPayload) sink.payloads.get(1);
        assertEquals("EXECUTION", error.code());
        assertTrue(error.retryable());
        assertFalse(service.hasActiveRun(99L));
    }

    @Test
    void emitsAnswerWithCitationsAndPersistsAssistantMessage() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        AgentRunner runner = (request, cancellation, observer) -> new AgentAnswerResult(
                "东镇大街的牛腩口感很好，食材新鲜，但价格偏贵。",
                List.of(new AgentCitation(42, "牛腩探店")));
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, Runnable::run);

        service.start(7, 99L, new AgentRunCommand("哪里有好吃的牛腩？", 0, null, null, null), sink);

        assertEquals(List.of(
                AgentSseEventType.RUN_STARTED,
                AgentSseEventType.CITATION,
                AgentSseEventType.ANSWER,
                AgentSseEventType.RUN_COMPLETED), sink.types());
        assertEquals("东镇大街的牛腩口感很好，食材新鲜，但价格偏贵。",
                ((AnswerPayload) sink.payloads.get(2)).answer());
        verify(sessions).appendMessage(
                7, 99L, AgentMessageRole.ASSISTANT, "东镇大街的牛腩口感很好，食材新鲜，但价格偏贵。");
    }

    @Test
    void injectsTimeAndWeatherContextIntoRunRequest() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        WeatherService weather = mock(WeatherService.class);
        when(weather.fetchWeather(anyDouble(), anyDouble())).thenReturn(sampleWeather());
        List<AgentRunRequest> captured = new ArrayList<>();
        AgentRunner runner = (request, cancellation, observer) -> {
            captured.add(request);
            return new AgentQuestionResult("q");
        };
        AgentRunService service = new AgentRunService(
                sessions, runner, Runnable::run, new ObjectMapper(), () -> "run-1", weather, null);

        service.start(
                7,
                99L,
                new AgentRunCommand("哪里有好吃的牛腩？", 0, null, null, null, null, 116.4, 39.9),
                new RecordingSink());

        AgentRunContext context = captured.get(0).context();
        assertNotNull(context);
        assertTrue(context.timeText().contains("星期"));
        assertTrue(context.weatherText().contains("User location: 北京"));
        assertTrue(context.weatherText().contains("Current weather: 26°C, 小雨"));
        assertTrue(context.weatherText().contains("12:00 24°C 中雨"));
    }

    @Test
    void weatherFailureDegradesToNullContextWithoutFailingTheRun() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        WeatherService weather = mock(WeatherService.class);
        when(weather.fetchWeather(anyDouble(), anyDouble())).thenThrow(new RuntimeException("qweather down"));
        List<AgentRunRequest> captured = new ArrayList<>();
        AgentRunner runner = (request, cancellation, observer) -> {
            captured.add(request);
            return new AgentQuestionResult("q");
        };
        RecordingSink sink = new RecordingSink();
        AgentRunService service = new AgentRunService(
                sessions, runner, Runnable::run, new ObjectMapper(), () -> "run-1", weather, null);

        service.start(7, 99L, new AgentRunCommand("问", 0, null, null, null), sink);

        AgentRunContext context = captured.get(0).context();
        assertNotNull(context);
        assertNull(context.weatherText());
        assertEquals(List.of(
                AgentSseEventType.RUN_STARTED,
                AgentSseEventType.QUESTION,
                AgentSseEventType.RUN_COMPLETED), sink.types());
    }

    @Test
    void streamsModelDeltasAsMessageDeltaEvents() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        AgentRunner runner = (request, cancellation, observer) -> {
            observer.onModelDelta("正在检索");
            observer.onModelDelta("历史帖子");
            return new AgentQuestionResult("q");
        };
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, Runnable::run);

        service.start(7, 99L, new AgentRunCommand("问", 0, null, null, null), sink);

        // 第一个增量立即刷出（后续的在节流窗内可能合并/丢弃，终态事件兜底完整正文）
        assertEquals(AgentSseEventType.MESSAGE_DELTA, sink.types().get(1));
        assertEquals("正在检索", ((MessageDeltaPayload) sink.payloads.get(1)).text());
    }

    @Test
    void buildsRunHistoryThroughTheContextPlanner() {
        // 会话带滚动摘要 → 传给 AgentRunner 的历史应包含摘要 SystemMessage + 逐字消息
        AgentSessionService sessions = mock(AgentSessionService.class);
        AgentSession session = new AgentSession();
        session.setId(99L);
        session.setUid(7);
        session.setContextSummary("User previously asked about noodle shops.");
        session.setSummarizedMessageId(1L);
        com.example.agent.session.AgentMessage recent =
                new com.example.agent.session.AgentMessage();
        recent.setId(2L);
        recent.setSessionId(99L);
        recent.setRole(AgentMessageRole.USER);
        recent.setContent("any cheaper options?");
        when(sessions.load(7, 99L)).thenReturn(new AgentSessionAggregate(
                session,
                List.of(recent),
                List.of(),
                null));
        List<List<Message>> capturedHistories = new ArrayList<>();
        AgentRunner runner = (request, cancellation, observer) -> {
            capturedHistories.add(request.history());
            return new AgentQuestionResult("q");
        };
        AgentRunService service = service(sessions, runner, Runnable::run);

        service.start(7, 99L, new AgentRunCommand("问", 0, null, null, null), new RecordingSink());

        assertEquals(1, capturedHistories.size());
        List<Message> history = capturedHistories.get(0);
        assertEquals(2, history.size()); // 摘要 SystemMessage + 1 条逐字消息（id>覆盖点）
        org.springframework.ai.chat.messages.SystemMessage summary =
                assertInstanceOf(org.springframework.ai.chat.messages.SystemMessage.class, history.get(0));
        assertTrue(summary.getText().contains("noodle shops"));
        assertTrue(history.get(1).getText().contains("any cheaper options?"));
    }

    @Test
    void emitsAndPersistsContextNoticeWhenObserverReportsDegradation() {
        AgentSessionService sessions = sessionsWithOwnedSession(7, 99L);
        AgentRunner runner = (request, cancellation, observer) -> {
            observer.onContextNotice("Earlier conversation exceeded the model window.");
            return new AgentQuestionResult("q");
        };
        RecordingSink sink = new RecordingSink();
        AgentRunService service = service(sessions, runner, Runnable::run);

        service.start(7, 99L, new AgentRunCommand("问", 0, null, null, null), sink);

        // 通知事件既推 SSE 又落库（会话恢复时可重放）
        assertTrue(sink.types().contains(AgentSseEventType.CONTEXT_NOTICE));
        ContextNoticePayload notice = (ContextNoticePayload) sink.payloads.get(1);
        assertEquals("Earlier conversation exceeded the model window.", notice.text());
        verify(sessions).appendEvent(
                org.mockito.ArgumentMatchers.eq(7),
                org.mockito.ArgumentMatchers.eq(99L),
                org.mockito.ArgumentMatchers.eq("run-1"),
                anyInt(),
                org.mockito.ArgumentMatchers.eq("context_notice"),
                anyString());
    }

    private WeatherVO sampleWeather() {
        WeatherVO vo = new WeatherVO();
        JSONObject location = new JSONObject();
        location.put("name", "北京");
        vo.setLocation(location);
        JSONObject now = new JSONObject();
        now.put("temp", "26");
        now.put("text", "小雨");
        vo.setNow(now);
        JSONObject hour = new JSONObject();
        hour.put("fxTime", "2026-08-30T12:00+08:00");
        hour.put("temp", "24");
        hour.put("text", "中雨");
        JSONArray hourly = new JSONArray();
        hourly.add(hour);
        vo.setHourly(hourly);
        return vo;
    }

    private AgentRunService service(AgentSessionService sessions, AgentRunner runner, Executor executor) {
        return new AgentRunService(sessions, runner, executor, new ObjectMapper(), () -> "run-1");
    }

    private AgentSessionService sessionsWithOwnedSession(int uid, long sessionId) {
        AgentSessionService sessions = mock(AgentSessionService.class);
        AgentSession session = new AgentSession();
        session.setId(sessionId);
        session.setUid(uid);
        when(sessions.load(uid, sessionId)).thenReturn(new AgentSessionAggregate(
                session,
                List.of(),
                List.of(),
                null));
        return sessions;
    }

    private static final class RecordingSink implements AgentEventSink {
        private final List<AgentSseEventType> events = new ArrayList<>();
        private final List<String> eventIds = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private boolean completed;

        @Override
        public void emit(AgentSseEventType type, String eventId, Object payload) {
            events.add(type);
            eventIds.add(eventId);
            payloads.add(payload);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable error) {
            throw new AssertionError(error);
        }

        List<AgentSseEventType> types() {
            return List.copyOf(events);
        }

        List<String> ids() {
            return List.copyOf(eventIds);
        }
    }

    private static final class QueueExecutor implements Executor {
        private final Queue<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.add(command);
        }

        void runNext() {
            queue.remove().run();
        }
    }
}
