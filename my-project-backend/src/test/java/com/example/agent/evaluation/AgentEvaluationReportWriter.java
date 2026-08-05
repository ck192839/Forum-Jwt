package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class AgentEvaluationReportWriter {
    private final ObjectMapper objectMapper;

    AgentEvaluationReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(
            Path backendProjectDirectory,
            String chatModel,
            String embeddingModel,
            List<AgentCaseEvaluation> agentResults,
            List<RetrievalCaseEvaluation> retrievalResults,
            AgentEvaluationSummary summary
    ) {
        try {
            Path fixedOutputDirectory = backendProjectDirectory.toAbsolutePath().normalize()
                    .resolve("target")
                    .resolve("agent-evaluation");
            Files.createDirectories(fixedOutputDirectory);
            EvaluationReport report = new EvaluationReport(
                    Instant.now().toString(),
                    chatModel,
                    embeddingModel,
                    List.copyOf(agentResults),
                    List.copyOf(retrievalResults),
                    summary
            );
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(fixedOutputDirectory.resolve("report.json").toFile(), report);
            Files.writeString(
                    fixedOutputDirectory.resolve("report.md"),
                    markdown(report),
                    StandardCharsets.UTF_8
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to write Agent evaluation report", exception);
        }
    }

    private String markdown(EvaluationReport report) {
        AgentEvaluationSummary summary = report.summary();
        StringBuilder content = new StringBuilder("""
                # Forum Authoring Agent Evaluation

                - Generated: %s
                - Chat model: `%s`
                - Embedding model: `%s`
                - Overall gate: **%s**

                ## Quality Gates

                | Metric | Result | Required |
                |---|---:|---:|
                | Structured terminal output | %s | 100%% |
                | Safety | %s | 100%% |
                | Tool-call limit | %s | 100%% |
                | Recall@5 | %s | >= 80%% |
                | Indexing/setup latency | %d ms | Report only |
                | Retrieval query latency | %d ms | Report only |
                | Agent latency | %d ms | Report only |
                | Total latency | %d ms | Report only |
                | Total tokens | %d | Report only |

                ## Agent Cases

                | Case | Structure | Safety | <= 8 tools | Latency (ms) | Tokens | Tools | Error |
                |---|---|---|---|---:|---:|---|---|
                """.formatted(
                report.generatedAt(),
                report.chatModel(),
                report.embeddingModel(),
                summary.passed() ? "PASS" : "FAIL",
                percent(summary.structureRate()),
                percent(summary.safetyRate()),
                percent(summary.toolLimitRate()),
                percent(summary.recallAtFive()),
                summary.indexingSetupLatencyMillis(),
                summary.retrievalQueryLatencyMillis(),
                summary.agentLatencyMillis(),
                summary.totalLatencyMillis(),
                summary.totalTokens()
        ));
        for (AgentCaseEvaluation result : report.agentResults()) {
            content.append("| ").append(result.id())
                    .append(" | ").append(pass(result.structurePassed()))
                    .append(" | ").append(pass(result.safetyPassed()))
                    .append(" | ").append(pass(result.toolLimitPassed()))
                    .append(" | ").append(result.latencyMillis())
                    .append(" | ").append(result.totalTokens())
                    .append(" | ").append(String.join(", ", result.tools()))
                    .append(" | ").append(result.error() == null ? "" : result.error().replace('|', '/'))
                    .append(" |\n");
        }
        content.append("""

                ## Retrieval Cases

                | Case | Healthy | Relevant topics | Top 5 topics | Latency (ms) | Error |
                |---|---|---|---|---:|---|
                """);
        for (RetrievalCaseEvaluation result : report.retrievalResults()) {
            content.append("| ").append(result.id())
                    .append(" | ").append(pass(result.retrievalPassed()))
                    .append(" | ").append(result.relevantTopicIds())
                    .append(" | ").append(result.retrievedTopicIds().stream().limit(5).toList())
                    .append(" | ").append(result.latencyMillis())
                    .append(" | ").append(result.error() == null ? "" : result.error().replace('|', '/'))
                    .append(" |\n");
        }
        return content.toString();
    }

    private String pass(boolean value) {
        return value ? "PASS" : "FAIL";
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private record EvaluationReport(
            String generatedAt,
            String chatModel,
            String embeddingModel,
            List<AgentCaseEvaluation> agentResults,
            List<RetrievalCaseEvaluation> retrievalResults,
            AgentEvaluationSummary summary
    ) {
    }
}
