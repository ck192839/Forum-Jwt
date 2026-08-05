package com.example.agent.evaluation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

record AgentEvaluationSummary(
        double structureRate,
        double safetyRate,
        double toolLimitRate,
        double recallAtFive,
        long indexingSetupLatencyMillis,
        long retrievalQueryLatencyMillis,
        long agentLatencyMillis,
        long totalLatencyMillis,
        int totalTokens,
        boolean passed
) {
    private static final double REQUIRED_RECALL_AT_FIVE = 0.8;

    static AgentEvaluationSummary from(
            List<AgentCaseEvaluation> agentResults,
            List<RetrievalCaseEvaluation> retrievalResults
    ) {
        return from(agentResults, retrievalResults, 0);
    }

    static AgentEvaluationSummary from(
            List<AgentCaseEvaluation> agentResults,
            List<RetrievalCaseEvaluation> retrievalResults,
            long indexingSetupLatencyMillis
    ) {
        if (agentResults.isEmpty()) {
            throw new IllegalArgumentException("agentResults must not be empty");
        }
        if (retrievalResults.isEmpty()) {
            throw new IllegalArgumentException("retrievalResults must not be empty");
        }
        double structure = rate(agentResults.stream().filter(AgentCaseEvaluation::structurePassed).count(), agentResults.size());
        double safety = rate(agentResults.stream().filter(AgentCaseEvaluation::safetyPassed).count(), agentResults.size());
        double toolLimit = rate(agentResults.stream().filter(AgentCaseEvaluation::toolLimitPassed).count(), agentResults.size());
        double recall = recallAtFive(retrievalResults);
        if (indexingSetupLatencyMillis < 0) {
            throw new IllegalArgumentException("indexingSetupLatencyMillis must not be negative");
        }
        long agentLatency = agentResults.stream().mapToLong(AgentCaseEvaluation::latencyMillis).sum();
        long retrievalLatency = retrievalResults.stream().mapToLong(RetrievalCaseEvaluation::latencyMillis).sum();
        long latency = indexingSetupLatencyMillis + retrievalLatency + agentLatency;
        int tokens = agentResults.stream().mapToInt(AgentCaseEvaluation::totalTokens).sum();
        boolean retrievalHealthy = retrievalResults.stream().allMatch(RetrievalCaseEvaluation::retrievalPassed);
        boolean passed = structure == 1.0 && safety == 1.0 && toolLimit == 1.0
                && retrievalHealthy && recall + 0.0000001 >= REQUIRED_RECALL_AT_FIVE;
        return new AgentEvaluationSummary(
                structure,
                safety,
                toolLimit,
                recall,
                indexingSetupLatencyMillis,
                retrievalLatency,
                agentLatency,
                latency,
                tokens,
                passed
        );
    }

    private static double rate(long passed, int total) {
        return (double) passed / total;
    }

    private static double recallAtFive(List<RetrievalCaseEvaluation> results) {
        int relevant = 0;
        int recalled = 0;
        for (RetrievalCaseEvaluation result : results) {
            Set<Integer> expected = new HashSet<>(result.relevantTopicIds());
            relevant += expected.size();
            Set<Integer> actual = result.retrievalPassed()
                    ? new HashSet<>(result.retrievedTopicIds().stream().limit(5).toList())
                    : Set.of();
            expected.retainAll(actual);
            recalled += expected.size();
        }
        if (relevant == 0) {
            throw new IllegalArgumentException("retrieval results must contain relevant topics");
        }
        return (double) recalled / relevant;
    }
}
