package com.example.search.evaluation;

import com.example.search.HybridTopicSearchService;
import com.example.search.KeywordTopicRetriever;
import com.example.search.TopicSearchHit;
import com.example.search.VectorTopicRetriever;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

final class HybridRetrievalEvaluator {
    private final RecordingKeywordRetriever keywordRetriever;
    private final RecordingVectorRetriever vectorRetriever;
    private final HybridTopicSearchService searchService;

    HybridRetrievalEvaluator(
            KeywordTopicRetriever keywordRetriever,
            VectorTopicRetriever vectorRetriever
    ) {
        this.keywordRetriever = new RecordingKeywordRetriever(keywordRetriever);
        this.vectorRetriever = new RecordingVectorRetriever(vectorRetriever);
        this.searchService = new HybridTopicSearchService(this.keywordRetriever, this.vectorRetriever);
    }

    HybridTopicSearchService searchService() {
        return searchService;
    }

    List<RetrievalCaseEvaluation> evaluate(RetrievalEvaluationDataset dataset) {
        List<RetrievalCaseEvaluation> results = new ArrayList<>();
        for (RetrievalEvaluationDataset.RetrievalCase testCase : dataset.retrievalCases()) {
            keywordRetriever.reset();
            vectorRetriever.reset();
            Instant started = Instant.now();
            try {
                List<Integer> retrieved = searchService.search(testCase.query()).stream()
                        .map(ranked -> ranked.topic().topicId())
                        .toList();
                RuntimeException failure = firstFailure();
                if (failure != null) {
                    results.add(failed(testCase, started, failure));
                } else {
                    results.add(new RetrievalCaseEvaluation(
                            testCase.id(),
                            testCase.relevantTopicIds(),
                            retrieved,
                            Duration.between(started, Instant.now()).toMillis(),
                            true,
                            null
                    ));
                }
            } catch (RuntimeException exception) {
                results.add(failed(testCase, started, exception));
            }
        }
        return results;
    }

    private RuntimeException firstFailure() {
        if (keywordRetriever.failure != null) {
            return keywordRetriever.failure;
        }
        return vectorRetriever.failure;
    }

    private RetrievalCaseEvaluation failed(
            RetrievalEvaluationDataset.RetrievalCase testCase,
            Instant started,
            RuntimeException exception
    ) {
        return new RetrievalCaseEvaluation(
                testCase.id(),
                testCase.relevantTopicIds(),
                List.of(),
                Duration.between(started, Instant.now()).toMillis(),
                false,
                exception.getClass().getSimpleName() + ": " + exception.getMessage()
        );
    }

    private static final class RecordingKeywordRetriever implements KeywordTopicRetriever {
        private final KeywordTopicRetriever delegate;
        private RuntimeException failure;

        private RecordingKeywordRetriever(KeywordTopicRetriever delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<TopicSearchHit> search(String query) {
            try {
                return delegate.search(query);
            } catch (RuntimeException exception) {
                failure = exception;
                throw exception;
            }
        }

        private void reset() {
            failure = null;
        }
    }

    private static final class RecordingVectorRetriever implements VectorTopicRetriever {
        private final VectorTopicRetriever delegate;
        private RuntimeException failure;

        private RecordingVectorRetriever(VectorTopicRetriever delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<TopicSearchHit> search(String query) {
            try {
                return delegate.search(query);
            } catch (RuntimeException exception) {
                failure = exception;
                throw exception;
            }
        }

        private void reset() {
            failure = null;
        }
    }
}
