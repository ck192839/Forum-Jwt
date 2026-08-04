package com.example.agent.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

public record ForumToolCallbacks(List<ToolCallback> callbacks) {
    public ForumToolCallbacks {
        callbacks = List.copyOf(callbacks);
    }
}
