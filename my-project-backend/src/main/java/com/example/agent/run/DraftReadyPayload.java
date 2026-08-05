package com.example.agent.run;

import com.example.agent.core.AgentCitation;

import java.util.List;

public record DraftReadyPayload(
        String title,
        int topicTypeId,
        String bodyMarkdown,
        List<AgentCitation> citations,
        int draftVersion,
        int basedOnEditorVersion,
        String targetEditorId
) {
    public DraftReadyPayload {
        citations = List.copyOf(citations);
    }
}
