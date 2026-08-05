package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record AgentEvaluationDataset(
        List<AgentCase> agentCases,
        List<CorpusTopic> corpus,
        List<RetrievalCase> retrievalCases
) {
    AgentEvaluationDataset {
        agentCases = List.copyOf(agentCases);
        corpus = List.copyOf(corpus);
        retrievalCases = List.copyOf(retrievalCases);
        validate(agentCases, corpus, retrievalCases);
    }

    static AgentEvaluationDataset load(ObjectMapper objectMapper, String classpathResource) {
        try (InputStream input = AgentEvaluationDataset.class.getResourceAsStream(classpathResource)) {
            if (input == null) {
                throw new IllegalArgumentException("Evaluation resource not found: " + classpathResource);
            }
            return objectMapper.readValue(input, AgentEvaluationDataset.class);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to load evaluation resource: " + classpathResource, exception);
        }
    }

    private static void validate(
            List<AgentCase> agentCases,
            List<CorpusTopic> corpus,
            List<RetrievalCase> retrievalCases
    ) {
        requireUnique(agentCases.stream().map(AgentCase::id).toList(), "agent case");
        requireUnique(retrievalCases.stream().map(RetrievalCase::id).toList(), "retrieval case");
        Set<Integer> topicIds = new HashSet<>(corpus.stream().map(CorpusTopic::topicId).toList());
        if (topicIds.size() != corpus.size()) {
            throw new IllegalArgumentException("Corpus topic ids must be unique");
        }
        for (AgentCase testCase : agentCases) {
            if (!Set.of("QUESTION", "DRAFT").contains(testCase.expectedType())) {
                throw new IllegalArgumentException("Unknown expected type for " + testCase.id());
            }
        }
        for (RetrievalCase testCase : retrievalCases) {
            if (testCase.relevantTopicIds().isEmpty() || !topicIds.containsAll(testCase.relevantTopicIds())) {
                throw new IllegalArgumentException("Invalid retrieval ground truth for " + testCase.id());
            }
        }
    }

    private static <T> void requireUnique(List<T> ids, String label) {
        if (ids.stream().anyMatch(id -> id == null || String.valueOf(id).isBlank())
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(label + " ids must be non-empty and unique");
        }
    }

    record AgentCase(
            String id,
            String prompt,
            String expectedType,
            List<String> requiredTools,
            boolean promptInjection,
            List<String> forbiddenPhrases
    ) {
        AgentCase {
            requiredTools = List.copyOf(requiredTools);
            forbiddenPhrases = List.copyOf(forbiddenPhrases);
        }
    }

    record CorpusTopic(int topicId, String title, String body, int topicTypeId) {
    }

    record RetrievalCase(String id, String query, List<Integer> relevantTopicIds) {
        RetrievalCase {
            relevantTopicIds = List.copyOf(relevantTopicIds);
        }
    }
}
