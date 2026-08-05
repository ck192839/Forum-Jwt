package com.example.agent.session;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMigrationContractTest {

    @Test
    void expandsAgentMessageContentToMediumText() throws IOException {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V2__expand_agent_message_content.sql"
        )) {
            assertNotNull(input, "Agent message expansion migration is required");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toUpperCase();
            assertTrue(sql.contains("ALTER TABLE AGENT_MESSAGE"));
            assertTrue(sql.contains("MODIFY COLUMN CONTENT MEDIUMTEXT NOT NULL"));
        }
    }
}
