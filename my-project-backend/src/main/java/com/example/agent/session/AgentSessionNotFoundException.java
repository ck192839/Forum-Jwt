package com.example.agent.session;

public class AgentSessionNotFoundException extends RuntimeException {
    public AgentSessionNotFoundException() {
        super("Agent session not found");
    }
}
