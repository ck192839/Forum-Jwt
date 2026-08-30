package com.example.agent.config;

import com.example.agent.core.AgentRunBudgets;
import com.example.agent.core.AgentTerminalResultParser;
import com.example.agent.core.ForumReActAgent;
import com.example.agent.context.AgentContextPlanner;
import com.example.agent.context.AgentContextProperties;
import com.example.agent.context.AgentSessionSummarizer;
import com.example.agent.run.AgentRunService;
import com.example.agent.search.HybridTopicSearchService;
import com.example.agent.session.AgentSessionMapper;
import com.example.agent.session.AgentSessionService;
import com.example.agent.tool.ForumAuthoringTools;
import com.example.agent.tool.ForumToolCallbacks;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.service.WeatherService;
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

import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent 运行时的核心装配类：把所有 Agent 组件组合成一个可用的闭环。
 *
 * 装配顺序（依赖方向）：
 * 工具层：ForumAuthoringTools（4 个 @Tool 方法）
 * → MethodToolCallbackProvider 转成 ToolCallback 列表 → ForumToolCallbacks
 * 解析层：AgentTerminalResultParser（严格解析 DRAFT/QUESTION）
 * 核心层：ForumReActAgent（ReAct 循环，依赖 ChatModel + 工具 + 解析器 + 两个线程池）
 * 编排层：AgentRunService（会话编排 + 事件落库 + SSE 推送）
 *
 * 重要：AgentRunService / ForumReActAgent 都在这里用 new 显式创建，
 * 不是组件扫描，所以构造器不需要 @Autowired（Spring 不参与构造器选择）。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentRuntimeProperties.class, AgentContextProperties.class})
public class AgentRuntimeConfiguration {

    /**
     * 声明式工具对象：4 个 @Tool 方法（list_topic_types / search_similar_topics /
     * read_public_topic / validate_draft）。
     * 依赖论坛的只读 Mapper + 混合检索 + 违禁词工具，构成 Agent 唯一的数据访问面。
     * 截断上限（excerpt/正文）由 agent.context.* 配置注入——工具出口是上下文膨胀的第一道闸。
     */
    @Bean
    ForumAuthoringTools forumAuthoringTools(
            TopicTypeMapper topicTypeMapper,
            TopicMapper topicMapper,
            HybridTopicSearchService searchService,
            ProhibitedUtils prohibitedUtils,
            AgentContextProperties contextProperties) {
        return new ForumAuthoringTools(
                topicTypeMapper,
                topicMapper,
                searchService,
                prohibitedUtils,
                contextProperties.getExcerptMaxChars(),
                contextProperties.getReadTopicMaxChars());
    }

    /**
     * 把 @Tool 注解的方法反射转成 Spring AI 的 ToolCallback 列表，
     * 包装成不可变的 ForumToolCallbacks（record）。
     */
    @Bean
    ForumToolCallbacks forumToolCallbacks(ForumAuthoringTools tools) {
        return new ForumToolCallbacks(Arrays.asList(
                MethodToolCallbackProvider.builder()
                        .toolObjects(tools)
                        .build()
                        .getToolCallbacks()));
    }

    /** 终端结果解析器：把模型输出的 JSON 严格解析成 QUESTION / DRAFT。 */
    @Bean
    AgentTerminalResultParser agentTerminalResultParser(ObjectMapper objectMapper) {
        return new AgentTerminalResultParser(objectMapper);
    }

    /**
     * 工具调用管理器：负责按模型返回的 toolCalls 执行工具并拼接对话历史。
     * internalToolExecutionEnabled 关闭（在 Agent 里配置），由 ForumReActAgent 手动控制执行时机。
     */
    @Bean
    ToolCallingManager forumToolCallingManager() {
        return ToolCallingManager.builder().build();
    }

    /**
     * 「单次调用」线程池（默认 8 线程，线程名前缀 agent-call-）。
     * 用途：每次模型调用 / 工具调用都丢到这里执行，
     * 这样 ForumReActAgent 才能用 future.get(timeout) 实现超时中断和取消。
     * 必须独立于 run 线程池，否则超时取消会互相阻塞。
     */
    @Bean(name = "agentCallExecutor", destroyMethod = "shutdown")
    ExecutorService agentCallExecutor(AgentRuntimeProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getCallThreads(),
                new CustomizableThreadFactory("agent-call-"));
    }

    /**
     * 「整个 run」线程池（默认 4 线程，线程名前缀 agent-run-）。
     * 用途：每个 Agent 会话的 run 在这里异步执行，不阻塞 HTTP 请求线程。
     */
    @Bean(name = "agentRunExecutor", destroyMethod = "shutdown")
    ExecutorService agentRunExecutor(AgentRuntimeProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getRunThreads(),
                new CustomizableThreadFactory("agent-run-"));
    }

    /**
     * 滚动摘要任务线程池（默认 2 线程，线程名前缀 agent-summarize-）。
     * 线程间按会话互斥（AgentSessionSummarizer 内部加锁）：同一会话串行保证正确性，
     * 不同会话并行，避免全局单线程排队拉大摘要延迟。
     */
    @Bean(name = "agentSummarizeExecutor", destroyMethod = "shutdown")
    ExecutorService agentSummarizeExecutor(AgentContextProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getSummarizeThreads(),
                new CustomizableThreadFactory("agent-summarize-"));
    }

    /** prompt 历史预算组装器：摘要 SystemMessage + 近期逐字窗口，双上限裁剪。 */
    @Bean
    AgentContextPlanner agentContextPlanner(AgentContextProperties properties) {
        return new AgentContextPlanner(
                properties.getMaxContextTokens(),
                properties.getRecentWindowMessages());
    }

    /** 会话滚动摘要器：run 结束后异步压缩旧消息进持久化摘要（失败静默，下次重试）。 */
    @Bean
    AgentSessionSummarizer agentSessionSummarizer(
            AgentSessionMapper sessionMapper,
            AgentSessionService sessionService,
            ChatModel chatModel,
            @Qualifier("agentSummarizeExecutor") ExecutorService summarizeExecutor,
            AgentContextProperties properties) {
        return new AgentSessionSummarizer(
                sessionMapper,
                sessionService,
                chatModel,
                summarizeExecutor,
                properties.getRecentWindowMessages(),
                properties.getSummarizeMarginMessages(),
                properties.getSummaryMaxChars(),
                properties.getSummarizeTimeout().getSeconds());
    }

    /**
     * 核心 Agent：ReAct 循环。
     * 参数说明：
     * - chatModel ：DeepSeek 聊天模型（支持流式）
     * - toolCallingManager / callbacks：工具执行能力
     * - callExecutor ：单次调用线程池（超时/取消用）
     * - budgets ：run 资源预算（工具次数 / 工具 token / 用户请求截断 / 工具结果上界，供最坏情况预检）
     * - timeout：总时间预算（默认 60 秒）
     */
    @Bean
    ForumReActAgent forumReActAgent(
            ChatModel chatModel,
            @Qualifier("forumToolCallingManager") ToolCallingManager toolCallingManager,
            ForumToolCallbacks callbacks,
            AgentTerminalResultParser resultParser,
            @Qualifier("agentCallExecutor") ExecutorService callExecutor,
            AgentRuntimeProperties properties,
            AgentContextProperties contextProperties) {
        return new ForumReActAgent(
                chatModel,
                toolCallingManager,
                callbacks.callbacks(),
                resultParser,
                callExecutor,
                new AgentRunBudgets(
                        properties.getMaxToolCalls(),
                        contextProperties.getMaxToolTokens(),
                        contextProperties.getMaxUserMessageChars(),
                        contextProperties.getReadTopicMaxChars(),
                        contextProperties.getExcerptMaxChars()),
                properties.getTimeout());
    }

    /**
     * 运行编排服务：会话加载 → 消息落库 → 异步执行 Agent → 事件落库 + SSE 推送。
     * 注入 WeatherService（时间/天气上下文）、Clock（可注入测试时钟）、
     * 上下文预算组装器与滚动摘要器（run 结束后台压缩旧历史）。
     * 测试里可用短构造器禁用天气上下文与摘要器。
     */
    @Bean
    AgentRunService agentRunService(
            AgentSessionService sessionService,
            ForumReActAgent agent,
            @Qualifier("agentRunExecutor") ExecutorService runExecutor,
            ObjectMapper objectMapper,
            WeatherService weatherService,
            Clock clock,
            AgentContextPlanner contextPlanner,
            AgentSessionSummarizer summarizer) {
        return new AgentRunService(
                sessionService, agent, runExecutor, objectMapper,
                () -> UUID.randomUUID().toString(), weatherService, clock,
                contextPlanner, summarizer);
    }
}
