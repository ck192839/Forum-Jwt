package com.example.agent.config;

import com.example.agent.core.AgentTerminalResultParser;
import com.example.agent.core.ForumReActAgent;
import com.example.agent.run.AgentRunService;
import com.example.agent.search.HybridTopicSearchService;
import com.example.agent.session.AgentSessionService;
import com.example.agent.tool.ForumAuthoringTools;
import com.example.agent.tool.ForumToolCallbacks;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.utils.ProhibitedUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeConfiguration {

    @Bean
    ForumAuthoringTools forumAuthoringTools(
            TopicTypeMapper topicTypeMapper,
            TopicMapper topicMapper,
            HybridTopicSearchService searchService,
            ProhibitedUtils prohibitedUtils
    ) {
        return new ForumAuthoringTools(topicTypeMapper, topicMapper, searchService, prohibitedUtils);
    }

    @Bean
    ForumToolCallbacks forumToolCallbacks(ForumAuthoringTools tools) {
        return new ForumToolCallbacks(Arrays.asList(
                MethodToolCallbackProvider.builder()
                        .toolObjects(tools)
                        .build()
                        .getToolCallbacks()
        ));
    }

    @Bean
    AgentTerminalResultParser agentTerminalResultParser(ObjectMapper objectMapper) {
        return new AgentTerminalResultParser(objectMapper);
    }

    @Bean
    ToolCallingManager forumToolCallingManager() {
        return ToolCallingManager.builder().build();
    }

    @Bean(name = "agentCallExecutor", destroyMethod = "shutdown")
    ExecutorService agentCallExecutor(AgentRuntimeProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getCallThreads(),
                new CustomizableThreadFactory("agent-call-")
        );
    }

    @Bean(name = "agentRunExecutor", destroyMethod = "shutdown")
    ExecutorService agentRunExecutor(AgentRuntimeProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getRunThreads(),
                new CustomizableThreadFactory("agent-run-")
        );
    }

    @Bean
    ForumReActAgent forumReActAgent(
            ChatModel chatModel,
            @Qualifier("forumToolCallingManager") ToolCallingManager toolCallingManager,
            ForumToolCallbacks callbacks,
            AgentTerminalResultParser resultParser,
            @Qualifier("agentCallExecutor") ExecutorService callExecutor,
            AgentRuntimeProperties properties
    ) {
        return new ForumReActAgent(
                chatModel,
                toolCallingManager,
                callbacks.callbacks(),
                resultParser,
                callExecutor,
                properties.getMaxToolCalls(),
                properties.getTimeout()
        );
    }

    @Bean
    AgentRunService agentRunService(
            AgentSessionService sessionService,
            ForumReActAgent agent,
            @Qualifier("agentRunExecutor") ExecutorService runExecutor,
            ObjectMapper objectMapper
    ) {
        return new AgentRunService(sessionService, agent, runExecutor, objectMapper);
    }
}
