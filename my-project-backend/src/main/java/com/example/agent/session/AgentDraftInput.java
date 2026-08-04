package com.example.agent.session;

public record AgentDraftInput(
        int editorVersion,
        String title,
        int topicTypeId,
        String bodyMarkdown,
        String citationsJson
) {
}
