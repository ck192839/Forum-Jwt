package com.example.agent.core;

import com.example.agent.search.HybridTopicSearchService;
import com.example.agent.search.TopicSearchHit;
import com.example.agent.tool.ForumAuthoringTools;
import com.example.entity.dto.TopicType;
import com.example.mapper.TopicMapper;
import com.example.mapper.TopicTypeMapper;
import com.example.utils.ProhibitedUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ForumReActAgentTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutDownExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void returnsAQuestionWithoutCallingTools() {
        ChatModel model = prompt -> response("""
                {"type":"QUESTION","question":"Who is the intended audience?"}
                """);
        RecordingObserver observer = new RecordingObserver();
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

        AgentTerminalResult result = agent.run(
                new AgentRunRequest("Help me write a post", 3, List.of()),
                new AgentCancellationToken(),
                observer
        );

        assertInstanceOf(AgentQuestionResult.class, result);
        assertEquals(List.of(), observer.started);
    }

    @Test
    void executesToolsAndAllowsOnlyToolReturnedCitations() {
        Deque<ChatResponse> responses = new ArrayDeque<>();
        responses.add(toolCall("call-1", "search_similar_topics", "{\"query\":\"network issue\"}"));
        responses.add(toolCall(
                "call-2",
                "validate_draft",
                "{\"title\":\"Network troubleshooting\",\"topicTypeId\":3,"
                        + "\"bodyMarkdown\":\"Try these steps.\"}"
        ));
        responses.add(response("""
                {"type":"DRAFT","title":"Network troubleshooting","topicTypeId":3,
                 "bodyMarkdown":"Try these steps.",
                 "citations":[{"topicId":42,"title":"Previous guide"}],
                 "basedOnEditorVersion":7}
                """));
        List<Prompt> prompts = new ArrayList<>();
        ChatModel model = prompt -> {
            prompts.add(prompt);
            return responses.removeFirst();
        };
        ForumAuthoringTools tools = toolsReturningTopic(42);
        RecordingObserver observer = new RecordingObserver();
        ForumReActAgent agent = agent(model, tools, 8, Duration.ofSeconds(2));

        AgentTerminalResult result = agent.run(
                new AgentRunRequest("Draft a network help post", 7, List.of()),
                new AgentCancellationToken(),
                observer
        );

        AgentDraftResult draft = assertInstanceOf(AgentDraftResult.class, result);
        assertEquals(42, draft.citations().get(0).topicId());
        assertEquals(List.of("search_similar_topics", "validate_draft"), observer.started);
        assertEquals(List.of("search_similar_topics", "validate_draft"), observer.completed);
        assertTrue(prompts.get(1).getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance));
    }

    @Test
    void rejectsDraftThatWasNotValidatedByTheDraftTool() {
        ChatModel model = prompt -> response("""
                {"type":"DRAFT","title":"Unvalidated","topicTypeId":3,
                 "bodyMarkdown":"Body","citations":[],"basedOnEditorVersion":1}
                """);
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

        AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                new AgentRunRequest("Write", 1, List.of()),
                new AgentCancellationToken(),
                AgentRunObserver.NOOP
        ));

        assertEquals(AgentRunFailure.INVALID_RESPONSE, exception.failure());
    }

    @Test
    void rejectsMoreThanEightToolCalls() {
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            int call = modelCalls.incrementAndGet();
            return toolCall("call-" + call, "list_topic_types", "{}");
        };
        RecordingObserver observer = new RecordingObserver();
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(5));

        AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                new AgentRunRequest("Write", 1, List.of()),
                new AgentCancellationToken(),
                observer
        ));

        assertEquals(AgentRunFailure.TOOL_LIMIT, exception.failure());
        assertEquals(9, modelCalls.get());
        assertEquals(8, observer.completed.size());
    }

    @Test
    void enforcesHardExecutionTimeout() {
        CountDownLatch entered = new CountDownLatch(1);
        ChatModel model = prompt -> {
            entered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return response("{\"type\":\"QUESTION\",\"question\":\"late\"}");
        };
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofMillis(120));
        long started = System.nanoTime();

        AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                new AgentRunRequest("Write", 1, List.of()),
                new AgentCancellationToken(),
                AgentRunObserver.NOOP
        ));

        assertEquals(AgentRunFailure.TIMEOUT, exception.failure());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        assertEquals(0, entered.getCount());
    }

    @Test
    void cancelsARunningModelCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        ChatModel model = prompt -> {
            entered.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return response("{\"type\":\"QUESTION\",\"question\":\"late\"}");
        };
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(5));
        AgentCancellationToken cancellation = new AgentCancellationToken();
        ExecutorService runExecutor = executor();
        Future<AgentTerminalResult> future = runExecutor.submit(() -> agent.run(
                new AgentRunRequest("Write", 1, List.of()),
                cancellation,
                AgentRunObserver.NOOP
        ));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        cancellation.cancel();

        ExecutionException execution = assertThrows(ExecutionException.class, () -> future.get(2, TimeUnit.SECONDS));
        AgentRunException exception = assertInstanceOf(AgentRunException.class, execution.getCause());
        assertEquals(AgentRunFailure.CANCELLED, exception.failure());
    }

    @Test
    void rejectsAlreadyCancelledRunBeforeCallingModel() {
        ChatModel model = mock(ChatModel.class);
        AgentCancellationToken cancellation = new AgentCancellationToken();
        cancellation.cancel();
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

        AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                new AgentRunRequest("Write", 1, List.of()),
                cancellation,
                AgentRunObserver.NOOP
        ));

        assertEquals(AgentRunFailure.CANCELLED, exception.failure());
        verify(model, never()).call(org.mockito.ArgumentMatchers.any(Prompt.class));
    }

    @Test
    void systemPromptTreatsTopicTextAsUntrustedAndForbidsPublishingOrThoughtDisclosure() {
        List<Prompt> prompts = new ArrayList<>();
        ChatModel model = prompt -> {
            prompts.add(prompt);
            return response("{\"type\":\"QUESTION\",\"question\":\"Need details\"}");
        };
        ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

        agent.run(
                new AgentRunRequest("Write", 1, List.of()),
                new AgentCancellationToken(),
                AgentRunObserver.NOOP
        );

        String system = prompts.get(0).getInstructions().stream()
                .filter(SystemMessage.class::isInstance)
                .findFirst()
                .orElseThrow()
                .getText();
        assertTrue(system.contains("untrusted"));
        assertTrue(system.contains("never publish"));
        assertTrue(system.contains("Do not reveal chain-of-thought"));
    }

    private ForumReActAgent agent(
            ChatModel model,
            ForumAuthoringTools tools,
            int maxToolCalls,
            Duration timeout
    ) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks();
        return new ForumReActAgent(
                model,
                ToolCallingManager.builder().build(),
                List.of(callbacks),
                new AgentTerminalResultParser(new ObjectMapper()),
                executor(),
                maxToolCalls,
                timeout
        );
    }

    private ForumAuthoringTools emptyTools() {
        TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
        when(typeMapper.selectList(null)).thenReturn(List.of());
        return new ForumAuthoringTools(
                typeMapper,
                mock(TopicMapper.class),
                new HybridTopicSearchService(query -> List.of(), query -> List.of()),
                mock(ProhibitedUtils.class)
        );
    }

    private ForumAuthoringTools toolsReturningTopic(int topicId) {
        TopicSearchHit hit = new TopicSearchHit(topicId, "Previous guide", "Excerpt", 3);
        TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
        when(typeMapper.selectById(3)).thenReturn(new TopicType());
        return new ForumAuthoringTools(
                typeMapper,
                mock(TopicMapper.class),
                new HybridTopicSearchService(query -> List.of(hit), query -> List.of(hit)),
                mock(ProhibitedUtils.class)
        );
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newCachedThreadPool();
        executors.add(executor);
        return executor;
    }

    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private ChatResponse toolCall(String id, String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static final class RecordingObserver implements AgentRunObserver {
        private final List<String> started = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();

        @Override
        public void toolStarted(String name, String arguments) {
            started.add(name);
        }

        @Override
        public void toolCompleted(String name, String result) {
            completed.add(name);
        }
    }
}
