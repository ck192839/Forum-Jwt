package com.example.agent.run;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;

public final class SseEmitterAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter;
    private final Runnable disconnectHandler;
    private final Object lifecycleMonitor = new Object();
    private Lifecycle lifecycle = Lifecycle.OPEN;

    public SseEmitterAgentEventSink(SseEmitter emitter, Runnable disconnectHandler) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.disconnectHandler = Objects.requireNonNull(disconnectHandler, "disconnectHandler");
        emitter.onTimeout(this::disconnect);
        emitter.onError(error -> disconnect());
        emitter.onCompletion(this::disconnect);
    }

    @Override
    public void emit(AgentSseEventType type, String eventId, Object payload) {
        synchronized (lifecycleMonitor) {
            if (lifecycle == Lifecycle.TERMINATED) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .id(eventId)
                        .name(type.wireName())
                        .data(payload));
            } catch (IOException | RuntimeException exception) {
                terminateDisconnected(exception);
            }
        }
    }

    @Override
    public void complete() {
        synchronized (lifecycleMonitor) {
            if (terminate()) {
                bestEffort(emitter::complete);
            }
        }
    }

    @Override
    public void completeWithError(Throwable error) {
        synchronized (lifecycleMonitor) {
            if (terminate()) {
                bestEffort(() -> emitter.completeWithError(error));
            }
        }
    }

    private void disconnect() {
        synchronized (lifecycleMonitor) {
            if (terminate()) {
                bestEffort(disconnectHandler);
            }
        }
    }

    private void terminateDisconnected(Throwable error) {
        if (terminate()) {
            bestEffort(disconnectHandler);
            bestEffort(() -> emitter.completeWithError(error));
        }
    }

    private boolean terminate() {
        if (lifecycle == Lifecycle.TERMINATED) {
            return false;
        }
        lifecycle = Lifecycle.TERMINATED;
        return true;
    }

    private void bestEffort(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            // The response may already be committed or disconnected.
        }
    }

    private enum Lifecycle {
        OPEN,
        TERMINATED
    }
}
