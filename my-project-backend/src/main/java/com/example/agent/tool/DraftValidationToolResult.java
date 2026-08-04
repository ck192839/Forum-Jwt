package com.example.agent.tool;

import java.util.List;

public record DraftValidationToolResult(boolean valid, List<String> errors) {
    public DraftValidationToolResult {
        errors = List.copyOf(errors);
    }
}
