package com.example.agent.config;

import com.example.agent.core.AgentTerminalResultParser;
import com.example.agent.core.ForumReActAgent;
import com.example.agent.run.AgentRunService;
import com.example.agent.search.HybridTopicSearchService;
import com.example.agent.tool.ForumAuthoringTools;
import com.example.agent.tool.ForumToolCallbacks;
import com.example.agent.session.AgentSessionService;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.utils.ProhibitedUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class AgentRuntimeConfigurationTest {

    @Test
    void defaultsToApprovedExecutionLimits() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();

        assertEquals(8, properties.getMaxToolCalls());
        assertEquals(Duration.ofSeconds(60), properties.getTimeout());
        assertEquals(4, properties.getRunThreads());
        assertEquals(8, properties.getCallThreads());
    }

    @Test
    void exposesExactlyTheFourApprovedNonPublishingTools() {
        AgentRuntimeConfiguration configuration = new AgentRuntimeConfiguration();
        ForumAuthoringTools tools = configuration.forumAuthoringTools(
                mock(TopicTypeMapper.class),
                mock(TopicMapper.class),
                mock(HybridTopicSearchService.class),
                mock(ProhibitedUtils.class)
        );

        ForumToolCallbacks callbacks = configuration.forumToolCallbacks(tools);

        Set<String> names = callbacks.callbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "list_topic_types",
                "search_similar_topics",
                "read_public_topic",
                "validate_draft"
        ), names);
    }

    @Test
    void wiresAgentAndRunServicesWithManagedExecutors() {
        AgentRuntimeConfiguration configuration = new AgentRuntimeConfiguration();
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        ExecutorService callExecutor = configuration.agentCallExecutor(properties);
        ExecutorService runExecutor = configuration.agentRunExecutor(properties);
        try {
            AgentTerminalResultParser parser = configuration.agentTerminalResultParser(objectMapper);
            ForumReActAgent agent = configuration.forumReActAgent(
                    mock(ChatModel.class),
                    ToolCallingManager.builder().build(),
                    new ForumToolCallbacks(java.util.List.of()),
                    parser,
                    callExecutor,
                    properties
            );
            AgentRunService runs = configuration.agentRunService(
                    mock(AgentSessionService.class),
                    agent,
                    runExecutor,
                    objectMapper,
                    mock(com.example.service.WeatherService.class),
                    java.time.Clock.systemUTC()
            );

            assertNotNull(agent);
            assertNotNull(runs);
        } finally {
            callExecutor.shutdownNow();
            runExecutor.shutdownNow();
        }
    }
}
