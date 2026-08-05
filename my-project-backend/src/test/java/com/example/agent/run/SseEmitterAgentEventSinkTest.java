package com.example.agent.run;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void serializesSendWithEveryTerminationPathAndCancelsAtMostOnce() throws Exception {
        for (Termination termination : Termination.values()) {
            BlockingEmitter emitter = new BlockingEmitter();
            AtomicInteger cancellations = new AtomicInteger();
            SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, cancellations::incrementAndGet);
            AtomicReference<Throwable> sendFailure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    sink.emit(AgentSseEventType.MESSAGE_DELTA, "run-1:1", new MessageDeltaPayload("text"));
                } catch (Throwable throwable) {
                    sendFailure.set(throwable);
                }
            });
            sender.start();
            assertTrue(emitter.sendEntered.await(1, TimeUnit.SECONDS));

            CountDownLatch terminationFinished = new CountDownLatch(1);
            Thread terminator = new Thread(() -> {
                try {
                    termination.invoke(sink, emitter);
                } finally {
                    terminationFinished.countDown();
                }
            });
            terminator.start();
            boolean terminatedDuringSend = terminationFinished.await(200, TimeUnit.MILLISECONDS);
            emitter.allowSendToFinish.countDown();
            sender.join(1_000);
            terminator.join(1_000);

            assertFalse(terminatedDuringSend, termination + " must wait for the active send");
            assertFalse(emitter.terminalDuringSend.get(), termination + " reached the emitter during send");
            assertFalse(sender.isAlive());
            assertFalse(terminator.isAlive());
            assertNull(sendFailure.get());
            int expectedCancellations = termination == Termination.COMPLETE ? 0 : 1;
            assertEquals(expectedCancellations, cancellations.get(), termination.toString());

            emitter.triggerTimeout();
            emitter.triggerError();
            emitter.triggerCompletion();
            assertEquals(expectedCancellations, cancellations.get(), termination + " cancelled more than once");
        }
    }

    @Test
    void illegalStateSendFailureIsContainedAndCancelsOnlyOnce() {
        RecordingEmitter emitter = new RecordingEmitter();
        IllegalStateException failure = new IllegalStateException("response already committed");
        emitter.runtimeSendFailure = failure;
        AtomicInteger cancellations = new AtomicInteger();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, cancellations::incrementAndGet);

        sink.emit(AgentSseEventType.MESSAGE_DELTA, "run-1:1", new MessageDeltaPayload("text"));
        emitter.triggerTimeout();
        emitter.triggerError();
        emitter.triggerCompletion();

        assertEquals(1, cancellations.get());
        assertSame(failure, emitter.error);
    }

    private enum Termination {
        COMPLETE {
            @Override
            void invoke(SseEmitterAgentEventSink sink, BlockingEmitter emitter) {
                sink.complete();
            }
        },
        TIMEOUT {
            @Override
            void invoke(SseEmitterAgentEventSink sink, BlockingEmitter emitter) {
                emitter.triggerTimeout();
            }
        },
        ERROR {
            @Override
            void invoke(SseEmitterAgentEventSink sink, BlockingEmitter emitter) {
                emitter.triggerError();
            }
        },
        COMPLETION {
            @Override
            void invoke(SseEmitterAgentEventSink sink, BlockingEmitter emitter) {
                emitter.triggerCompletion();
            }
        };

        abstract void invoke(SseEmitterAgentEventSink sink, BlockingEmitter emitter);
    }

    private static final class RecordingEmitter extends SseEmitter {
        private static final Pattern EVENT_LINE = Pattern.compile("(?m)^event:([^\\r\\n]+)$");

        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> data = new ArrayList<>();
        private boolean completed;
        private Throwable error;
        private IOException sendFailure;
        private RuntimeException runtimeSendFailure;
        private Runnable timeoutHandler;
        private Consumer<Throwable> errorHandler;
        private Runnable completionHandler;

        private RecordingEmitter() {
            super(1_000L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) throws IOException {
            if (sendFailure != null) {
                throw sendFailure;
            }
            if (runtimeSendFailure != null) {
                throw runtimeSendFailure;
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

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorHandler = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionHandler = callback;
        }

        void triggerTimeout() {
            timeoutHandler.run();
        }

        void triggerError() {
            errorHandler.accept(new IOException("client disconnected"));
        }

        void triggerCompletion() {
            completionHandler.run();
        }
    }

    private static final class BlockingEmitter extends SseEmitter {
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        private final CountDownLatch allowSendToFinish = new CountDownLatch(1);
        private final AtomicBoolean sending = new AtomicBoolean();
        private final AtomicBoolean terminalDuringSend = new AtomicBoolean();
        private Runnable timeoutHandler;
        private Consumer<Throwable> errorHandler;
        private Runnable completionHandler;

        private BlockingEmitter() {
            super(1_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sending.set(true);
            sendEntered.countDown();
            try {
                if (!allowSendToFinish.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("test send timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            } finally {
                sending.set(false);
            }
        }

        @Override
        public void complete() {
            recordTerminalCall();
        }

        @Override
        public void completeWithError(Throwable ex) {
            recordTerminalCall();
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeoutHandler = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorHandler = callback;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completionHandler = callback;
        }

        void triggerTimeout() {
            timeoutHandler.run();
        }

        void triggerError() {
            errorHandler.accept(new IOException("client disconnected"));
        }

        void triggerCompletion() {
            completionHandler.run();
        }

        private void recordTerminalCall() {
            if (sending.get()) {
                terminalDuringSend.set(true);
            }
        }
    }
}
