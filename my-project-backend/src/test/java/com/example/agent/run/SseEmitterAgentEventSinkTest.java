package com.example.agent.run;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void sendAndErrorCallbackWithReverseSpringLockOrderDoNotDeadlock() throws Exception {
        ReverseLockEmitter emitter = new ReverseLockEmitter();
        AtomicInteger cancellations = new AtomicInteger();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, cancellations::incrementAndGet);
        CountDownLatch senderFinished = new CountDownLatch(1);
        CountDownLatch errorFinished = new CountDownLatch(1);
        Thread errorThread = daemonThread(() -> {
            emitter.springWriteLock.lock();
            try {
                emitter.errorLockHeld.countDown();
                await(emitter.allowErrorCallback);
                emitter.triggerError();
            } finally {
                emitter.springWriteLock.unlock();
                errorFinished.countDown();
            }
        });
        Thread senderThread = daemonThread(() -> {
            try {
                sink.emit(AgentSseEventType.MESSAGE_DELTA, "run-1:1", new MessageDeltaPayload("text"));
            } finally {
                senderFinished.countDown();
            }
        });

        errorThread.start();
        assertTrue(emitter.errorLockHeld.await(1, TimeUnit.SECONDS));
        senderThread.start();
        assertTrue(emitter.sendEntered.await(1, TimeUnit.SECONDS));
        emitter.allowErrorCallback.countDown();
        boolean completedWithoutDeadlock;
        try {
            completedWithoutDeadlock = senderFinished.await(500, TimeUnit.MILLISECONDS)
                    && errorFinished.await(500, TimeUnit.MILLISECONDS);
        } finally {
            senderThread.interrupt();
            emitter.allowErrorCallback.countDown();
            senderThread.join(1_000);
            errorThread.join(1_000);
        }

        assertTrue(completedWithoutDeadlock, "send and onError formed an AB-BA deadlock");
        assertFalse(senderThread.isAlive());
        assertFalse(errorThread.isAlive());
        assertEquals(1, cancellations.get());
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

    @Test
    void programmingRuntimeFailureFromSendPropagatesWithoutCancellation() {
        RecordingEmitter emitter = new RecordingEmitter();
        NullPointerException failure = new NullPointerException("programming error");
        emitter.runtimeSendFailure = failure;
        AtomicInteger cancellations = new AtomicInteger();
        SseEmitterAgentEventSink sink = new SseEmitterAgentEventSink(emitter, cancellations::incrementAndGet);

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> sink.emit(
                AgentSseEventType.MESSAGE_DELTA,
                "run-1:1",
                new MessageDeltaPayload("text")
        ));

        assertSame(failure, thrown);
        assertEquals(0, cancellations.get());
        assertNull(emitter.error);
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

    private static Thread daemonThread(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class ReverseLockEmitter extends SseEmitter {
        private final ReentrantLock springWriteLock = new ReentrantLock();
        private final CountDownLatch errorLockHeld = new CountDownLatch(1);
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        private final CountDownLatch allowErrorCallback = new CountDownLatch(1);
        private Consumer<Throwable> errorHandler;

        private ReverseLockEmitter() {
            super(1_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendEntered.countDown();
            try {
                springWriteLock.lockInterruptibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            springWriteLock.unlock();
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            errorHandler = callback;
        }

        void triggerError() {
            errorHandler.accept(new IOException("client disconnected"));
        }
    }
}
