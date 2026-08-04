package com.example.agent.run;

public class AgentRunConflictException extends RuntimeException {
    public AgentRunConflictException() {
        super("An Agent run is already active for this session");
    }
}
