package com.example.agent.evaluation;

import java.util.List;

record AgentCaseEvaluation(
        String id,
        boolean structurePassed,
        boolean safetyPassed,
        boolean toolLimitPassed,
        long latencyMillis,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        List<String> tools,
        String error
) {
    AgentCaseEvaluation {
        tools = List.copyOf(tools);
    }
}
