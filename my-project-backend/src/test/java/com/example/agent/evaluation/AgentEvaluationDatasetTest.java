package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationDatasetTest {
    private static final String COMPLETE_DRAFT_INSTRUCTION = "以下信息完整，直接生成草稿，不要追问可选信息";

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
        Map<String, AgentEvaluationDataset.AgentCase> casesById = dataset.agentCases().stream()
                .collect(Collectors.toMap(AgentEvaluationDataset.AgentCase::id, Function.identity()));
        assertTrue(casesById.get("draft_lost_card").prompt().contains("无需公开姓名或卡号"));
        assertTrue(casesById.get("draft_mental_health").prompt().contains("400-161-9995"));
        assertTrue(casesById.get("draft_sports_booking").prompt().contains("08:00-22:00"));
        assertTrue(casesById.get("draft_campus_wifi").prompt().contains("010-12345678"));
        assertTrue(casesById.get("draft_dorm_repair").prompt().contains("2 小时"));
        assertTrue(casesById.get("draft_exam_room").prompt().contains("迟到超过 15 分钟不得入场"));
        assertTrue(casesById.get("draft_scholarship").prompt().contains("9 月 30 日 17:00"));
    }

    @Test
    void draftCasesExplicitlyDeclareThatTheProvidedInformationIsComplete() {
        AgentEvaluationDataset dataset = AgentEvaluationDataset.load(
                new ObjectMapper(),
                "/agent-evaluation/cases.json"
        );

        assertEquals(15, dataset.agentCases().stream()
                .filter(testCase -> testCase.expectedType().equals("DRAFT"))
                .count());
        assertTrue(dataset.agentCases().stream()
                .filter(testCase -> testCase.expectedType().equals("DRAFT"))
                .allMatch(testCase -> testCase.prompt().contains(COMPLETE_DRAFT_INSTRUCTION)));
    }

    @Test
    void realEvaluationFailsInsteadOfSkippingWhenDockerIsUnavailable() {
        Testcontainers configuration = RealModelAgentEvaluationIT.class
                .getAnnotation(Testcontainers.class);

        assertFalse(configuration.disabledWithoutDocker());
    }
}
