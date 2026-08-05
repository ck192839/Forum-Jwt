package com.example.agent.run;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SseEmitterAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter;
    private final Runnable disconnectHandler;
    private final AtomicBoolean terminated = new AtomicBoolean();

    public SseEmitterAgentEventSink(SseEmitter emitter, Runnable disconnectHandler) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.disconnectHandler = Objects.requireNonNull(disconnectHandler, "disconnectHandler");
        emitter.onTimeout(this::disconnect);
        emitter.onError(error -> disconnect());
        emitter.onCompletion(this::disconnect);
    }

    @Override
    public void emit(AgentSseEventType type, String eventId, Object payload) {
        if (terminated.get()) {
            return;
        }
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(eventId)
                .name(type.wireName())
                .data(payload);
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            terminateDisconnected(exception);
        }
    }

    @Override
    public void complete() {
        if (terminated.compareAndSet(false, true)) {
            completeEmitter(emitter::complete);
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        if (terminated.compareAndSet(false, true)) {
            completeEmitter(() -> emitter.completeWithError(error));
        }
    }

    private void disconnect() {
        if (terminated.compareAndSet(false, true)) {
            disconnectHandler.run();
        }
    }

    private void terminateDisconnected(Throwable error) {
        if (terminated.compareAndSet(false, true)) {
            try {
                disconnectHandler.run();
            } finally {
                completeEmitter(() -> emitter.completeWithError(error));
            }
        }
    }

    private void completeEmitter(Runnable completion) {
        try {
            completion.run();
        } catch (IllegalStateException ignored) {
            // The servlet response was already completed by the container.
        }
    }
}
