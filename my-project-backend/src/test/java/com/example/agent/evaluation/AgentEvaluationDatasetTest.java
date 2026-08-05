package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationDatasetTest {

    @Test
    void loadsAtLeastTwentyRealModelAndRetrievalCases() {
        AgentEvaluationDataset dataset = AgentEvaluationDataset.load(
                new ObjectMapper(),
                "/agent-evaluation/cases.json"
        );

        assertTrue(dataset.agentCases().size() >= 20);
        assertTrue(dataset.retrievalCases().size() >= 20);
        assertTrue(dataset.corpus().size() >= 20);
        assertEquals(
                dataset.agentCases().size(),
                new HashSet<>(dataset.agentCases().stream().map(AgentEvaluationDataset.AgentCase::id).toList()).size()
        );
        assertEquals(
                dataset.retrievalCases().size(),
                new HashSet<>(dataset.retrievalCases().stream()
                        .map(AgentEvaluationDataset.RetrievalCase::id)
                        .toList()).size()
        );
        assertTrue(dataset.agentCases().stream().anyMatch(testCase -> testCase.promptInjection()));
        assertTrue(dataset.agentCases().stream().anyMatch(testCase -> testCase.expectedType().equals("QUESTION")));
        assertTrue(dataset.agentCases().stream().anyMatch(testCase -> testCase.expectedType().equals("DRAFT")));
        AgentEvaluationDataset.CorpusTopic injectionTopic = dataset.corpus().stream()
                .filter(topic -> topic.topicId() == 115)
                .findFirst()
                .orElseThrow();
        assertTrue(injectionTopic.body().contains(RealAgentEvaluator.INJECTION_MARKER));
        AgentEvaluationDataset.AgentCase injectionCase = dataset.agentCases().stream()
                .filter(AgentEvaluationDataset.AgentCase::promptInjection)
                .findFirst()
                .orElseThrow();
        assertTrue(injectionCase.forbiddenPhrases().contains("管理员公告"));
    }
}
