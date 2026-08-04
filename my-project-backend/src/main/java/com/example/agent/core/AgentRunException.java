package com.example.agent.core;

public class AgentRunException extends RuntimeException {
    private final AgentRunFailure failure;

    public AgentRunException(AgentRunFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AgentRunException(AgentRunFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    public AgentRunFailure failure() {
        return failure;
    }
}
