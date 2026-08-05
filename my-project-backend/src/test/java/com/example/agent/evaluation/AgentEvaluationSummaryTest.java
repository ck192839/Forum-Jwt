package com.example.agent.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationSummaryTest {

    @Test
    void requiresPerfectStructureSafetyAndToolLimitRates() {
        AgentEvaluationSummary summary = AgentEvaluationSummary.from(
                List.of(
                        agentResult("one", true, true, true),
                        agentResult("two", true, false, true)
                ),
                List.of(retrievalResult("query", List.of(1), List.of(1, 2, 3)))
        );

        assertFalse(summary.passed());
        assertTrue(summary.structureRate() == 1.0);
        assertTrue(summary.safetyRate() < 1.0);
    }

    @Test
    void computesRecallAtFiveAcrossAllRelevantTopics() {
        AgentEvaluationSummary summary = AgentEvaluationSummary.from(
                List.of(agentResult("one", true, true, true)),
                List.of(
                        retrievalResult("q1", List.of(1, 2), List.of(1, 9, 8, 7, 6, 2)),
                        retrievalResult("q2", List.of(3, 4, 5), List.of(3, 4, 5, 8, 9))
                )
        );

        assertTrue(Math.abs(summary.recallAtFive() - 0.8) < 0.0001);
        assertTrue(summary.passed());
    }

    @Test
    void separatesIndexingRetrievalAgentAndTotalLatency() {
        AgentEvaluationSummary summary = AgentEvaluationSummary.from(
                List.of(new AgentCaseEvaluation(
                        "one", true, true, true, 120, 100, 20, 120, List.of(), null
                )),
                List.of(new RetrievalCaseEvaluation("q1", List.of(1), List.of(1), 30)),
                45
        );

        assertTrue(summary.indexingSetupLatencyMillis() == 45);
        assertTrue(summary.retrievalQueryLatencyMillis() == 30);
        assertTrue(summary.agentLatencyMillis() == 120);
        assertTrue(summary.totalLatencyMillis() == 195);
    }

    private AgentCaseEvaluation agentResult(
            String id,
            boolean structure,
            boolean safety,
            boolean toolLimit
    ) {
        return new AgentCaseEvaluation(id, structure, safety, toolLimit, 120, 100, 20, 120, List.of(), null);
    }

    private RetrievalCaseEvaluation retrievalResult(
            String id,
            List<Integer> relevant,
            List<Integer> retrieved
    ) {
        return new RetrievalCaseEvaluation(id, relevant, retrieved, 30);
    }
}
