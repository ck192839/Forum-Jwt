package com.example.agent.run;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseEmitterAgentEventSinkTest {

    @Test
    void mapsEveryAgentEventToItsExactWireName() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, () -> { });

        for (AgentSseEventType type : AgentSseEventType.values()) {
            sink.emit(type, "run-1:1", new MessageDeltaPayload("visible output"));
        }

        assertEquals(List.of(
                "run_started", "message_delta", "tool_started", "tool_completed",
                "citation", "question", "draft_ready", "run_completed", "error"
        ), emitter.eventNames);
        assertTrue(emitter.data.stream().allMatch(MessageDeltaPayload.class::isInstance));
    }

    @Test
    void completesEmitterNormally() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, () -> { });

        sink.complete();

        assertTrue(emitter.completed);
    }

    @Test
    void sendFailureCompletesWithErrorAndCancelsRun() {
        RecordingEmitter emitter = new RecordingEmitter();
        emitter.sendFailure = new IOException("client disconnected");
        AtomicBoolean cancelled = new AtomicBoolean();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, () -> cancelled.set(true));

        sink.emit(AgentSseEventType.MESSAGE_DELTA, "run-1:1", new MessageDeltaPayload("text"));

        assertTrue(cancelled.get());
        assertEquals(emitter.sendFailure, emitter.error);
    }

    @Test
    void explicitErrorCompletesEmitterWithError() {
        RecordingEmitter emitter = new RecordingEmitter();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, () -> { });
        IllegalStateException failure = new IllegalStateException("stream failed");

        sink.completeWithError(failure);

        assertEquals(failure, emitter.error);
    }

    @Test
    void emitterTimeoutCancelsRun() {
        RecordingEmitter emitter = new RecordingEmitter();
        AtomicBoolean cancelled = new AtomicBoolean();
        new SseEmitterAgentEventSink(emitter, () -> cancelled.set(true));

        emitter.timeoutHandler.run();

        assertTrue(cancelled.get());
    }

    private static final class RecordingEmitter extends SseEmitter {
        private static final Pattern EVENT_LINE = Pattern.compile("(?m)^event:([^\\r\\n]+)$");

        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> data = new ArrayList<>();
        private boolean completed;
        private Throwable error;
        private IOException sendFailure;
        private Runnable timeoutHandler;

        private RecordingEmitter() {
            super(1_000L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (sendFailure != null) {
                throw sendFailure;
            }
            for (DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof String line) {
                    Matcher matcher = EVENT_LINE.matcher(line);
                    if (matcher.find()) {
                        eventNames.add(matcher.group(1));
                    }
                } else {
                    data.add(item.getData());
                }
            }
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            error = ex;
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutHandler = callback;
        }
    }
}
