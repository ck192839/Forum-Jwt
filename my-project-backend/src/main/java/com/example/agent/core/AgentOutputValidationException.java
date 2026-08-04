package com.example.agent.core;

public class AgentOutputValidationException extends RuntimeException {
    public AgentOutputValidationException(String message) {
        super(message);
    }

    public AgentOutputValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
