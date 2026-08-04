package com.example.agent.run;

public interface AgentEventSink {
    void emit(AgentSseEventType type, String eventId, Object payload);

    void complete();

    void completeWithError(Throwable error);
}
