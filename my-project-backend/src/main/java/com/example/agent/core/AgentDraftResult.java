package com.example.agent.core;

import java.util.List;

public record AgentDraftResult(
        String title,
        int topicTypeId,
        String bodyMarkdown,
        List<AgentCitation> citations,
        int basedOnEditorVersion
) implements AgentTerminalResult {
    public AgentDraftResult {
        citations = List.copyOf(citations);
    }
}
