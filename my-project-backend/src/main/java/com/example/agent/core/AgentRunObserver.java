package com.example.agent.core;

public interface AgentRunObserver {
    AgentRunObserver NOOP = new AgentRunObserver() {
    };

    default void toolStarted(String name, String arguments) {
    }

    default void toolCompleted(String name, String result) {
    }
}
