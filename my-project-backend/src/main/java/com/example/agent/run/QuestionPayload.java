package com.example.agent.run;

public record QuestionPayload(
        String question,
        String targetEditorId,
        int basedOnEditorVersion
) {
    public QuestionPayload(String question) {
        this(question, null, 0);
    }
}
