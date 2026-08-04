package com.example.agent.tool;

public record PublicTopicToolResult(
        boolean found,
        Integer topicId,
        String title,
        Integer topicTypeId,
        String bodyText
) {
    public static PublicTopicToolResult notFound() {
        return new PublicTopicToolResult(false, null, null, null, null);
    }
}
