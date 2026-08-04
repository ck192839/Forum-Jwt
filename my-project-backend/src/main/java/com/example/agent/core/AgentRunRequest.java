package com.example.agent.core;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

public record AgentRunRequest(
        String userMessage,
        int editorVersion,
        List<Message> history
) {
    public AgentRunRequest {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage is required");
        }
        if (editorVersion < 0) {
            throw new IllegalArgumentException("editorVersion must be non-negative");
        }
        history = history == null ? List.of() : List.copyOf(history);
    }
}
