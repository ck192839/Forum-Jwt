package com.example.agent.session;

import java.util.List;

public record AgentSessionAggregate(
        AgentSession session,
        List<AgentMessage> messages,
        List<AgentEvent> events,
        AgentDraft draft
) {
}
