package com.example.search.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalEvaluationDatasetTest {

    @Test
    void loadsRealCorpusAndRetrievalCasesWithUniqueIds() {
        RetrievalEvaluationDataset dataset = RetrievalEvaluationDataset.load(
                new ObjectMapper(),
                "/search-evaluation/cases.json"
        );

        assertTrue(dataset.retrievalCases().size() >= 20);
        assertTrue(dataset.corpus().size() >= 20);
        assertEquals(
                dataset.retrievalCases().size(),
                new HashSet<>(dataset.retrievalCases().stream()
                        .map(RetrievalEvaluationDataset.RetrievalCase::id)
                        .toList()).size()
        );
        assertEquals(
                dataset.corpus().size(),
                new HashSet<>(dataset.corpus().stream()
                        .map(RetrievalEvaluationDataset.CorpusTopic::topicId)
                        .toList()).size()
        );
    }

    @Test
    void everyRetrievalCaseHasGroundTruthInsideCorpus() {
        RetrievalEvaluationDataset dataset = RetrievalEvaluationDataset.load(
                new ObjectMapper(),
                "/search-evaluation/cases.json"
        );

        assertTrue(dataset.retrievalCases().stream()
                .allMatch(testCase -> !testCase.relevantTopicIds().isEmpty()));
        var topicIds = dataset.corpus().stream()
                .map(RetrievalEvaluationDataset.CorpusTopic::topicId)
                .collect(Collectors.toSet());
        assertTrue(dataset.retrievalCases().stream()
                .allMatch(testCase -> topicIds.containsAll(testCase.relevantTopicIds())));
    }
}
