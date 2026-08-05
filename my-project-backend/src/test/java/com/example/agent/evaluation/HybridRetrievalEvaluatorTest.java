package com.example.agent.evaluation;

import com.example.agent.search.KeywordTopicRetriever;
import com.example.agent.search.TopicSearchHit;
import com.example.agent.search.VectorTopicRetriever;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrievalEvaluatorTest {

    @Test
    void evaluatesActualProductionRetrieverOutputsThroughProductionRrf() {
        KeywordTopicRetriever keyword = query -> List.of(hit(2), hit(1));
        VectorTopicRetriever vector = query -> List.of(hit(1), hit(2));
        HybridRetrievalEvaluator evaluator = evaluator(keyword, vector);

        List<RetrievalCaseEvaluation> results = evaluator.evaluate(dataset());

        assertEquals(List.of(1, 2), results.get(0).retrievedTopicIds());
        assertTrue(results.get(0).retrievalPassed());
        assertNotNull(evaluator.searchService());
    }

    @Test
    void vectorFailureProducesAFailedRecallInputInsteadOfKeywordOnlySuccess() {
        KeywordTopicRetriever keyword = query -> List.of(hit(1));
        VectorTopicRetriever vector = query -> {
            throw new IllegalStateException("embedding unavailable");
        };
        HybridRetrievalEvaluator evaluator = evaluator(keyword, vector);

        RetrievalCaseEvaluation result = evaluator.evaluate(dataset()).get(0);

        assertFalse(result.retrievalPassed());
        assertTrue(result.retrievedTopicIds().isEmpty());
        assertTrue(result.error().contains("embedding unavailable"));
        AgentEvaluationSummary summary = AgentEvaluationSummary.from(
                List.of(new AgentCaseEvaluation(
                        "agent", true, true, true, 1, 1, 1, 2, List.of(), null
                )),
                List.of(result)
        );
        assertEquals(0.0, summary.recallAtFive());
        assertFalse(summary.passed());
    }

    private AgentEvaluationDataset dataset() {
        return new AgentEvaluationDataset(
                List.of(),
                List.of(
                        new AgentEvaluationDataset.CorpusTopic(1, "Campus WiFi", "network authentication", 1),
                        new AgentEvaluationDataset.CorpusTopic(2, "Library hours", "open until 23:00", 2)
                ),
                List.of(new AgentEvaluationDataset.RetrievalCase(
                        "wifi",
                        "Campus WiFi authentication",
                        List.of(1)
                ))
        );
    }

    private TopicSearchHit hit(int topicId) {
        return new TopicSearchHit(topicId, "Topic " + topicId, "Excerpt " + topicId, topicId);
    }

    private HybridRetrievalEvaluator evaluator(
            KeywordTopicRetriever keyword,
            VectorTopicRetriever vector
    ) {
        return new HybridRetrievalEvaluator(keyword, vector);
    }
}
