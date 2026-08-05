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
        try {
            emitter.send(SseEmitter.event()
                    .id(eventId)
                    .name(type.wireName())
                    .data(payload));
        } catch (IOException exception) {
            if (terminated.compareAndSet(false, true)) {
                disconnectHandler.run();
                emitter.completeWithError(exception);
            }
        }
    }

    @Override
    public void complete() {
        if (terminated.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        if (terminated.compareAndSet(false, true)) {
            emitter.completeWithError(error);
        }
    }

    private void disconnect() {
        if (terminated.compareAndSet(false, true)) {
            disconnectHandler.run();
        }
    }
}
