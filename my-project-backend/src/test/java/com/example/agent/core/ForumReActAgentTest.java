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
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.ResponseFormat;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
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
                                observer);

                assertInstanceOf(AgentQuestionResult.class, result);
                assertEquals(List.of(), observer.started);
        }

        @Test
        void aggregatesStreamingModelOutputForTerminalResult() {
                ChatModel model = mock(ChatModel.class);
                when(((StreamingChatModel) model).stream(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(
                                Flux.just(
                                                response("{\"type\":\"QUES"),
                                                response("TION\",\"question\":\"Who is the audience?\"}")));
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentTerminalResult result = agent.run(
                                new AgentRunRequest("Help me write a post", 3, List.of()),
                                new AgentCancellationToken(),
                                observer);

                AgentQuestionResult questionResult = assertInstanceOf(AgentQuestionResult.class, result);
                assertEquals("Who is the audience?", questionResult.question());
        }

        @Test
        void treatsAToolEncodedQuestionAsATerminalResult() {
                ChatModel model = prompt -> toolCall(
                                "call-question",
                                "QUESTION",
                                "{\"question\":\"What time and location should be used?\"}");
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentQuestionResult result = assertInstanceOf(AgentQuestionResult.class, agent.run(
                                new AgentRunRequest("Help me write an event post", 3, List.of()),
                                new AgentCancellationToken(),
                                observer));

                assertEquals("What time and location should be used?", result.question());
                assertEquals(List.of(), observer.started);
        }

        @Test
        void repairsAnInvalidTerminalFormatWithinTheRunBudget() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(response("""
                                ```json
                                {"type":"QUESTION","question":"Who is the intended audience?"}
                                ```
                                """));
                responses.add(response("""
                                {"type":"QUESTION","question":"Who is the intended audience?"}
                                """));
                List<Prompt> prompts = new ArrayList<>();
                ChatModel model = prompt -> {
                        prompts.add(prompt);
                        return responses.removeFirst();
                };
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentTerminalResult result = agent.run(
                                new AgentRunRequest("Help me write a post", 3, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP);

                assertInstanceOf(AgentQuestionResult.class, result);
                assertEquals(2, prompts.size());
                assertTrue(prompts.get(1).getInstructions().stream()
                                .anyMatch(message -> message.getText().contains("without Markdown fences")));
        }

        @Test
        void rejectsAfterTwoInvalidTerminalRepairAttempts() {
                AtomicInteger modelCalls = new AtomicInteger();
                ChatModel model = prompt -> {
                        modelCalls.incrementAndGet();
                        return response("```json\n{\"type\":\"QUESTION\",\"question\":\"Need details\"}\n```");
                };
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                                new AgentRunRequest("Write", 1, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP));

                assertEquals(AgentRunFailure.INVALID_RESPONSE, exception.failure());
                assertEquals(3, modelCalls.get());
        }

        @Test
        void executesToolsAndAllowsOnlyToolReturnedCitations() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(toolCall("call-1", "search_similar_topics", "{\"query\":\"network issue\"}"));
                responses.add(toolCall(
                                "call-2",
                                "validate_draft",
                                "{\"title\":\"Network troubleshooting\",\"topicTypeId\":3,"
                                                + "\"bodyMarkdown\":\"Try these steps.\"}"));
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
                                observer);

                AgentDraftResult draft = assertInstanceOf(AgentDraftResult.class, result);
                assertEquals(42, draft.citations().get(0).topicId());
                assertEquals(List.of("search_similar_topics", "validate_draft"), observer.started);
                assertEquals(List.of("search_similar_topics", "validate_draft"), observer.completed);
                assertTrue(prompts.get(1).getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance));
        }

        @Test
        void returnsAValidatedDraftWithoutRequestingAnotherModelResponse() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(toolCall("call-1", "search_similar_topics", "{\"query\":\"network issue\"}"));
                responses.add(toolCall(
                                "call-2",
                                "validate_draft",
                                "{\"title\":\"Network troubleshooting\",\"topicTypeId\":3,"
                                                + "\"bodyMarkdown\":\"Try these steps.\"}"));
                AtomicInteger modelCalls = new AtomicInteger();
                ChatModel model = prompt -> {
                        modelCalls.incrementAndGet();
                        return responses.removeFirst();
                };
                ForumReActAgent agent = agent(model, toolsReturningTopic(42), 8, Duration.ofSeconds(2));

                AgentDraftResult draft = assertInstanceOf(AgentDraftResult.class, agent.run(
                                new AgentRunRequest("Draft a network help post", 7, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP));

                assertEquals(2, modelCalls.get());
                assertEquals("Network troubleshooting", draft.title());
                assertEquals(3, draft.topicTypeId());
                assertEquals("Try these steps.", draft.bodyMarkdown());
                assertEquals(7, draft.basedOnEditorVersion());
                assertEquals(List.of(new AgentCitation(42, "Previous guide")), draft.citations());
        }

        @Test
        void matchesAUniqueValidationResponseByToolNameWhenItsIdDiffers() {
                ChatResponse validationCall = toolCall(
                                "model-call-id",
                                "validate_draft",
                                "{\"title\":\"Network troubleshooting\",\"topicTypeId\":3,"
                                                + "\"bodyMarkdown\":\"Try these steps.\"}");
                ChatModel model = prompt -> validationCall;
                ToolResponseMessage responseMessage = ToolResponseMessage.builder()
                                .responses(List.of(new ToolResponseMessage.ToolResponse(
                                                "provider-response-id",
                                                "validate_draft",
                                                "{\"valid\":true,\"errors\":[]}")))
                                .build();
                ToolCallingManager manager = mock(ToolCallingManager.class);
                when(manager.executeToolCalls(
                                org.mockito.ArgumentMatchers.any(Prompt.class),
                                org.mockito.ArgumentMatchers.any(ChatResponse.class)))
                                .thenReturn(ToolExecutionResult.builder()
                                                .conversationHistory(List.of(responseMessage))
                                                .build());
                ForumReActAgent agent = agent(
                                model,
                                emptyTools(),
                                manager,
                                8,
                                Duration.ofSeconds(2));

                AgentDraftResult draft = assertInstanceOf(AgentDraftResult.class, agent.run(
                                new AgentRunRequest("Draft a network help post", 7, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP));

                assertEquals("Network troubleshooting", draft.title());
                assertEquals(7, draft.basedOnEditorVersion());
        }

        @Test
        void revalidatesAStrictTerminalDraftAfterAnEarlierValidationFailed() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(toolCall(
                                "call-1",
                                "validate_draft",
                                "{\"title\":\"This title is intentionally longer than thirty characters\","
                                                + "\"topicTypeId\":3,\"bodyMarkdown\":\"Try these steps.\"}"));
                responses.add(response("""
                                {"type":"DRAFT","title":"Network help","topicTypeId":3,
                                 "bodyMarkdown":"Try these steps.","citations":[],"basedOnEditorVersion":7}
                                """));
                AtomicInteger modelCalls = new AtomicInteger();
                ChatModel model = prompt -> {
                        modelCalls.incrementAndGet();
                        return responses.removeFirst();
                };
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, toolsReturningTopic(42), 8, Duration.ofSeconds(2));

                AgentDraftResult draft = assertInstanceOf(AgentDraftResult.class, agent.run(
                                new AgentRunRequest("Draft a network help post", 7, List.of()),
                                new AgentCancellationToken(),
                                observer));

                assertEquals(2, modelCalls.get());
                assertEquals("Network help", draft.title());
                assertEquals(List.of("validate_draft", "validate_draft"), observer.started);
                assertEquals(List.of("validate_draft", "validate_draft"), observer.completed);
        }

        @Test
        void usesNativeJsonObjectResponseFormatForToolAndTerminalCalls() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(toolCall("call-1", "list_topic_types", "{}"));
                responses.add(response("""
                                {"type":"QUESTION","question":"Which section should this target?"}
                                """));
                List<Prompt> prompts = new ArrayList<>();
                ChatModel model = prompt -> {
                        prompts.add(prompt);
                        return responses.removeFirst();
                };
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                agent.run(
                                new AgentRunRequest("Help me write a post", 1, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP);

                assertEquals(2, prompts.size());
                for (Prompt prompt : prompts) {
                        DeepSeekChatOptions options = assertInstanceOf(
                                        DeepSeekChatOptions.class,
                                        prompt.getOptions());
                        assertEquals(ResponseFormat.Type.JSON_OBJECT, options.getResponseFormat().getType());
                }
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
                                AgentRunObserver.NOOP));

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
                                observer));

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
                                AgentRunObserver.NOOP));

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
                                AgentRunObserver.NOOP));
                assertTrue(entered.await(1, TimeUnit.SECONDS));

                cancellation.cancel();

                ExecutionException execution = assertThrows(ExecutionException.class,
                                () -> future.get(2, TimeUnit.SECONDS));
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
                                AgentRunObserver.NOOP));

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
                                AgentRunObserver.NOOP);

                String system = prompts.get(0).getInstructions().stream()
                                .filter(SystemMessage.class::isInstance)
                                .findFirst()
                                .orElseThrow()
                                .getText();
                assertTrue(system.contains("untrusted"));
                assertTrue(system.contains("never publish"));
                assertTrue(system.contains("Do not reveal chain-of-thought"));
        }

        @Test
        void systemPromptRequiresAQuestionWhenCriticalFactsAreMissing() {
                List<Prompt> prompts = new ArrayList<>();
                ChatModel model = prompt -> {
                        prompts.add(prompt);
                        return response("{\"type\":\"QUESTION\",\"question\":\"What time and location should be used?\"}");
                };
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                agent.run(
                                new AgentRunRequest("Write an event post, but the time and location are unknown", 1,
                                                List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP);

                String system = prompts.get(0).getInstructions().stream()
                                .filter(SystemMessage.class::isInstance)
                                .findFirst()
                                .orElseThrow()
                                .getText();
                assertTrue(system.contains("critical facts"));
                assertTrue(system.contains("type field is \"QUESTION\""));
                assertTrue(system.contains("QUESTION is an output type, not a tool name"));
                assertTrue(system.contains("Never invent placeholders"));
                assertTrue(system.contains("Do not ask for optional details"));
        }

        private ForumReActAgent agent(
                        ChatModel model,
                        ForumAuthoringTools tools,
                        int maxToolCalls,
                        Duration timeout) {
                return agent(
                                model,
                                tools,
                                ToolCallingManager.builder().build(),
                                maxToolCalls,
                                timeout);
        }

        private ForumReActAgent agent(
                        ChatModel model,
                        ForumAuthoringTools tools,
                        ToolCallingManager toolCallingManager,
                        int maxToolCalls,
                        Duration timeout) {
                ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                                .toolObjects(tools)
                                .build()
                                .getToolCallbacks();
                return new ForumReActAgent(
                                model,
                                toolCallingManager,
                                List.of(callbacks),
                                new AgentTerminalResultParser(new ObjectMapper()),
                                executor(),
                                maxToolCalls,
                                timeout);
        }

        private ForumAuthoringTools emptyTools() {
                TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
                when(typeMapper.selectList(null)).thenReturn(List.of());
                return new ForumAuthoringTools(
                                typeMapper,
                                mock(TopicMapper.class),
                                new HybridTopicSearchService(query -> List.of(), query -> List.of()),
                                mock(ProhibitedUtils.class));
        }

        private ForumAuthoringTools toolsReturningTopic(int topicId) {
                TopicSearchHit hit = new TopicSearchHit(topicId, "Previous guide", "Excerpt", 3);
                TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
                when(typeMapper.selectById(3)).thenReturn(new TopicType());
                return new ForumAuthoringTools(
                                typeMapper,
                                mock(TopicMapper.class),
                                new HybridTopicSearchService(query -> List.of(hit), query -> List.of(hit)),
                                mock(ProhibitedUtils.class));
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
