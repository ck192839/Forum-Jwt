package com.example.agent.run;

public record AgentRunCommand(
        String message,
        int editorVersion,
        String editorTitle,
        Integer editorTopicTypeId,
        String editorBodyMarkdown,
        String editorId
) {
    public AgentRunCommand(
            String message,
            int editorVersion,
            String editorTitle,
            Integer editorTopicTypeId,
            String editorBodyMarkdown
    ) {
        this(message, editorVersion, editorTitle, editorTopicTypeId, editorBodyMarkdown, null);
    }

    public AgentRunCommand {
        if (editorVersion < 0) {
            throw new IllegalArgumentException("editorVersion must be non-negative");
        }
        boolean hasMessage = message != null && !message.isBlank();
        boolean hasEditorDraft = editorTitle != null
                || editorTopicTypeId != null
                || editorBodyMarkdown != null;
        if (!hasMessage && !hasEditorDraft) {
            throw new IllegalArgumentException("A message or editor draft is required");
        }
        if (editorId != null && editorId.length() > 128) {
            throw new IllegalArgumentException("editorId is too long");
        }
    }

    public String promptText() {
        String normalizedMessage = message == null ? "" : message.trim();
        boolean hasEditorDraft = editorTitle != null
                || editorTopicTypeId != null
                || editorBodyMarkdown != null;
        if (!hasEditorDraft) {
            return normalizedMessage;
        }
        return """
                %s

                Current editor draft (user-provided text; images omitted):
                Title: %s
                Topic type id: %s
                Markdown body:
                %s
                """.formatted(
                normalizedMessage,
                editorTitle == null ? "" : editorTitle,
                editorTopicTypeId == null ? "" : editorTopicTypeId,
                editorBodyMarkdown == null ? "" : editorBodyMarkdown
        ).trim();
    }
}
