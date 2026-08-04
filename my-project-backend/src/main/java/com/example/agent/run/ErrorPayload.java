package com.example.agent.run;

public record ErrorPayload(String runId, String code, String message, boolean retryable) {
}
