package com.example.agent.evaluation;

import java.util.List;

record RetrievalCaseEvaluation(
        String id,
        List<Integer> relevantTopicIds,
        List<Integer> retrievedTopicIds,
        long latencyMillis,
        boolean retrievalPassed,
        String error
) {
    RetrievalCaseEvaluation(
            String id,
            List<Integer> relevantTopicIds,
            List<Integer> retrievedTopicIds,
            long latencyMillis
    ) {
        this(id, relevantTopicIds, retrievedTopicIds, latencyMillis, true, null);
    }

    RetrievalCaseEvaluation {
        relevantTopicIds = List.copyOf(relevantTopicIds);
        retrievedTopicIds = List.copyOf(retrievedTopicIds);
    }
}
