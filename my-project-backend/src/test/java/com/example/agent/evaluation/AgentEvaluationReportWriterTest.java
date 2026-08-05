package com.example.agent.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEvaluationReportWriterTest {

    @TempDir
    Path backendProjectDirectory;

    @Test
    void writesJsonAndMarkdownWithGateLatencyAndTokenMetrics() throws Exception {
        List<AgentCaseEvaluation> agentResults = List.of(
                new AgentCaseEvaluation("draft_wifi", true, true, true, 450, 120, 80, 200,
                        List.of("search_similar_topics", "validate_draft"), null)
        );
        List<RetrievalCaseEvaluation> retrievalResults = List.of(
                new RetrievalCaseEvaluation("wifi", List.of(101), List.of(101, 102), 35)
        );
        AgentEvaluationSummary summary = AgentEvaluationSummary.from(agentResults, retrievalResults);

        AgentEvaluationReportWriter writer = new AgentEvaluationReportWriter(new ObjectMapper());
        writer.write(backendProjectDirectory, "deepseek-chat", "text-embedding-v4", agentResults, retrievalResults, summary);

        Path outputDirectory = backendProjectDirectory.resolve("target/agent-evaluation");
        assertTrue(Files.exists(outputDirectory.resolve("report.md")));
        assertTrue(Files.exists(outputDirectory.resolve("report.json")));
        String markdown = Files.readString(outputDirectory.resolve("report.md"));
        String json = Files.readString(outputDirectory.resolve("report.json"));
        assertTrue(Files.notExists(backendProjectDirectory.resolve("report.md")));
        assertTrue(markdown.contains("Recall@5"));
        assertTrue(markdown.contains("Indexing/setup latency"));
        assertTrue(markdown.contains("Retrieval query latency"));
        assertTrue(markdown.contains("Agent latency"));
        assertTrue(markdown.contains("100.00%"));
        assertTrue(markdown.contains("200"));
        assertTrue(json.contains("\"totalTokens\" : 200"));
        assertTrue(json.contains("\"passed\" : true"));
    }
}
