package com.example.agent.session;

public record AgentDraftInput(
        int editorVersion,
        String title,
        int topicTypeId,
        String bodyMarkdown,
        String citationsJson,
        String targetEditorId
) {
    public AgentDraftInput(
            int editorVersion,
            String title,
            int topicTypeId,
            String bodyMarkdown,
            String citationsJson
    ) {
        this(editorVersion, title, topicTypeId, bodyMarkdown, citationsJson, null);
    }
}
