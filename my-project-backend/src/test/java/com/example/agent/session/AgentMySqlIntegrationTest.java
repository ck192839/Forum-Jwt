package com.example.agent.session;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Testcontainers(disabledWithoutDocker = true)
class AgentMySqlIntegrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("forum")
            .withUsername("forum")
            .withPassword("forum")
            .withStartupTimeout(java.time.Duration.ofMinutes(2));

    private static MysqlDataSource dataSource;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void migrateNonEmptyForumSchema() throws Exception {
        dataSource = new MysqlDataSource();
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUser(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE forum_topic (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL)");
            statement.execute("INSERT INTO forum_topic (id, title) VALUES (1, 'Keep me')");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentSessionMapper.class);
        configuration.addMapper(AgentDraftMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanAgentRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM agent_session");
        }
    }

    @Test
    void migrationsPreserveExistingForumDataAndApplyTheAgentSchema() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertEquals("Keep me", scalar(connection, "SELECT title FROM forum_topic WHERE id = 1"));
            assertEquals("MEDIUMTEXT", scalar(connection, """
                    SELECT UPPER(DATA_TYPE)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'agent_message'
                      AND column_name = 'content'
                    """));
            assertEquals("VARCHAR", scalar(connection, """
                    SELECT UPPER(DATA_TYPE)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'agent_draft'
                      AND column_name = 'target_editor_id'
                    """));
            assertEquals(4L, number(connection, """
                    SELECT COUNT(*) FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_name LIKE 'agent_%'
                    """));
        }
    }

    @Test
    void repeatedDraftUpsertIncrementsVersionUsingRealMySql() throws Exception {
        long sessionId = insertSession(7, Instant.parse("2026-08-05T01:00:00Z"));
        Timestamp now = Timestamp.from(Instant.parse("2026-08-05T01:01:00Z"));
        AgentDraft draft = draft(sessionId, "First", 1, now);

        try (SqlSession sql = sessions.openSession(true)) {
            AgentDraftMapper mapper = sql.getMapper(AgentDraftMapper.class);
            mapper.upsert(draft);
            draft.setTitle("Second");
            draft.setEditorVersion(2);
            draft.setTargetEditorId("topic-editor-2");
            mapper.upsert(draft);

            AgentDraft persisted = mapper.selectBySessionId(sessionId);
            assertEquals(2, persisted.getVersion());
            assertEquals(2, persisted.getEditorVersion());
            assertEquals("topic-editor-2", persisted.getTargetEditorId());
            assertEquals("Second", persisted.getTitle());
        }
    }

    @Test
    void sessionCapAndOwnershipPredicatesAreEnforcedByRealSql() throws Exception {
        for (int index = 0; index < 12; index++) {
            insertSession(7, Instant.parse("2026-08-05T02:00:00Z").plusSeconds(index));
        }
        long otherUsersSession = insertSession(8, Instant.parse("2026-08-05T03:00:00Z"));

        try (SqlSession sql = sessions.openSession(true)) {
            AgentSessionMapper mapper = sql.getMapper(AgentSessionMapper.class);
            assertEquals(2, mapper.deleteOlderSessions(7, 10));
            assertEquals(10L, countSessions(7));
            assertNotNull(mapper.selectOwnedById(otherUsersSession, 8));
            assertNull(mapper.selectOwnedById(otherUsersSession, 7));
            assertEquals(0, mapper.deleteOwnedById(otherUsersSession, 7));
            assertEquals(0, mapper.touchOwnedById(otherUsersSession, 7, Timestamp.from(Instant.now())));
        }
        assertEquals(1L, countSessions(8));
    }

    private static AgentDraft draft(long sessionId, String title, int editorVersion, Timestamp now) {
        AgentDraft draft = new AgentDraft();
        draft.setSessionId(sessionId);
        draft.setVersion(1);
        draft.setEditorVersion(editorVersion);
        draft.setTitle(title);
        draft.setTopicTypeId(1);
        draft.setBodyMarkdown("Body");
        draft.setCitationsJson("[]");
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        return draft;
    }

    private static long insertSession(int uid, Instant updatedAt) throws Exception {
        String sql = """
                INSERT INTO agent_session (uid, status, created_at, updated_at, expires_at)
                VALUES (?, 'ACTIVE', ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            Timestamp timestamp = Timestamp.from(updatedAt);
            statement.setInt(1, uid);
            statement.setTimestamp(2, timestamp);
            statement.setTimestamp(3, timestamp);
            statement.setTimestamp(4, Timestamp.from(updatedAt.plusSeconds(86_400)));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static long countSessions(int uid) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM agent_session WHERE uid = ?"
             )) {
            statement.setInt(1, uid);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static long number(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
