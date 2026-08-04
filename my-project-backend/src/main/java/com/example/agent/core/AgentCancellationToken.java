package com.example.agent.core;

import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }
}
