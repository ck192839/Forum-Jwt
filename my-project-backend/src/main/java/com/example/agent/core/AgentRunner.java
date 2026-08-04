package com.example.agent.core;

@FunctionalInterface
public interface AgentRunner {
    AgentTerminalResult run(
            AgentRunRequest request,
            AgentCancellationToken cancellation,
            AgentRunObserver observer
    );
}
