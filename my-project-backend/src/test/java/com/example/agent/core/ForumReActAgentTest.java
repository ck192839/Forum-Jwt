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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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
        void streamsDecodedVisibleTextWhileTheAnswerIsStillBeingGenerated() {
                ChatModel model = mock(ChatModel.class);
                when(((StreamingChatModel) model).stream(org.mockito.ArgumentMatchers.any(Prompt.class))).thenReturn(
                                Flux.just(
                                                response("{\"type\":\"ANS"),
                                                response("WER\",\"answer\":\"东镇大街"),
                                                response("的牛腩口感很好，"),
                                                response("食材新鲜。\",\"citations\":[]}")));
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                agent.run(
                                new AgentRunRequest("哪里有好吃的牛腩？", 3, List.of()),
                                new AgentCancellationToken(),
                                observer);

                // 打字机效果：增量是解码后的正文（不含 JSON 结构），且按顺序拼接等于完整正文
                String streamed = String.join("", observer.deltas);
                assertEquals("东镇大街的牛腩口感很好，食材新鲜。", streamed);
                assertTrue(observer.deltas.stream().noneMatch(delta -> delta.contains("{")));
        }

        @Test
        void degradesToMinimalContextAndRetriesOnceOnContextOverflow() {
                List<Prompt> capturedPrompts = new ArrayList<>();
                AtomicInteger calls = new AtomicInteger();
                ChatModel model = prompt -> {
                        capturedPrompts.add(prompt);
                        if (calls.incrementAndGet() == 1) {
                                // 第一次调用：模拟 DeepSeek 上下文超长（错误信息在 cause 链里）
                                throw new RuntimeException(
                                                new IllegalStateException("This model's maximum context length is 4096 tokens"));
                        }
                        return response("{\"type\":\"QUESTION\",\"question\":\"Recovered\"}");
                };
                // 历史：早期问答 + 当前追问 → 正常 prompt 应为 系统 + 3 条历史 + 请求
                List<Message> history = List.of(
                                new UserMessage("earlier question"),
                                new AssistantMessage("earlier answer"),
                                new UserMessage("Follow-up question"));
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentQuestionResult result = assertInstanceOf(AgentQuestionResult.class, agent.run(
                                new AgentRunRequest("Follow-up question", 3, history),
                                new AgentCancellationToken(),
                                observer));

                assertEquals("Recovered", result.question());
                assertEquals(2, calls.get()); // 只重试一次
                assertEquals(5, capturedPrompts.get(0).getInstructions().size());
                // 降级后只保留 SystemMessage + 最后一条用户消息（assistant/tool 消息全部丢弃）
                List<Message> degraded = capturedPrompts.get(1).getInstructions();
                assertEquals(2, degraded.size());
                assertInstanceOf(SystemMessage.class, degraded.get(0));
                assertInstanceOf(UserMessage.class, degraded.get(1));
                assertTrue(((UserMessage) degraded.get(1)).getText().contains("Follow-up question"));
        }

        @Test
        void guidesTerminalResultInsteadOfFailingWhenToolCallLimitReached() {
                List<Prompt> capturedPrompts = new ArrayList<>();
                AtomicInteger round = new AtomicInteger();
                ChatModel model = prompt -> {
                        capturedPrompts.add(prompt);
                        if (round.incrementAndGet() == 1) {
                                // 第 1 轮：调用工具（消耗掉 maxToolCalls=1 的额度）
                                return toolCall("call-1", "search_similar_topics", "{\"query\":\"x\"}");
                        }
                        if (round.get() == 2) {
                                // 第 2 轮：模型无视额度仍要调用工具 → 应触发引导而非失败
                                return toolCall("call-2", "search_similar_topics", "{\"query\":\"y\"}");
                        }
                        return response("{\"type\":\"QUESTION\",\"question\":\"Best effort\"}");
                };
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, emptyTools(), 1, Duration.ofSeconds(5));

                AgentQuestionResult result = assertInstanceOf(AgentQuestionResult.class, agent.run(
                                new AgentRunRequest("q", 3, List.of()),
                                new AgentCancellationToken(),
                                observer));

                assertEquals("Best effort", result.question());
                assertEquals(3, round.get()); // 引导后模型收敛终态，run 未失败
                // 引导 prompt（第 3 次模型调用）的末尾是引导 UserMessage，且未执行的 tool_call assistant 消息被丢弃
                List<Message> guided = capturedPrompts.get(2).getInstructions();
                assertInstanceOf(UserMessage.class, guided.get(guided.size() - 1));
                assertTrue(((UserMessage) guided.get(guided.size() - 1)).getText().contains("tool budget"));
                // 第 1 轮已执行的调用（与 ToolResponse 配对）保留；未执行的第 2 次调用（call-2）不得出现
                assertTrue(guided.stream()
                                .filter(AssistantMessage.class::isInstance)
                                .map(AssistantMessage.class::cast)
                                .flatMap(message -> message.getToolCalls() == null
                                                ? java.util.stream.Stream.<AssistantMessage.ToolCall>empty()
                                                : message.getToolCalls().stream())
                                .noneMatch(call -> "call-2".equals(call.id())));
        }

        @Test
        void guidesTerminalResultWhenToolTokenBudgetExhausted() {
                AtomicInteger round = new AtomicInteger();
                ChatModel model = prompt -> {
                        // 第 1 轮：最坏情况预检（参数 + 结果上界）即超过 maxToolTokens=1 → 直接引导
                        if (round.incrementAndGet() == 1) {
                                return toolCall("call-1", "search_similar_topics", "{\"query\":\"x\"}");
                        }
                        return response("{\"type\":\"QUESTION\",\"question\":\"Recovered\"}");
                };
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(
                                model, toolsReturningTopic(42), ToolCallingManager.builder().build(),
                                8, 1, Duration.ofSeconds(5));

                AgentQuestionResult result = assertInstanceOf(AgentQuestionResult.class, agent.run(
                                new AgentRunRequest("q", 3, List.of()),
                                new AgentCancellationToken(),
                                observer));

                assertEquals("Recovered", result.question());
                assertEquals(2, round.get());
                assertEquals(0, observer.completed.size()); // 预检拦截，工具从未执行
        }

        @Test
        void failsHardWhenModelStillCallsToolsAfterBudgetGuidance() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(toolCall("call-1", "search_similar_topics", "{\"query\":\"a\"}"));
                responses.add(toolCall("call-2", "search_similar_topics", "{\"query\":\"b\"}"));
                responses.add(toolCall("call-3", "search_similar_topics", "{\"query\":\"c\"}"));
                ForumReActAgent agent = agent(
                                prompt -> responses.removeFirst(), emptyTools(), 1, Duration.ofSeconds(5));

                AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                                new AgentRunRequest("q", 3, List.of()),
                                new AgentCancellationToken(),
                                new RecordingObserver()));

                assertEquals(AgentRunFailure.TOOL_LIMIT, exception.failure());
        }

        @Test
        void truncatesOverlongUserRequestKeepingHeadAndTail() {
                List<Prompt> capturedPrompts = new ArrayList<>();
                ChatModel model = prompt -> {
                        capturedPrompts.add(prompt);
                        return response("{\"type\":\"QUESTION\",\"question\":\"ok\"}");
                };
                // 用户请求上限设为 100 字：超长请求应在出口截断
                ForumReActAgent agent = agent(
                                model, emptyTools(), ToolCallingManager.builder().build(),
                                new AgentRunBudgets(8, 12_000, 100, 8_000, 200),
                                Duration.ofSeconds(2));

                agent.run(
                                new AgentRunRequest("字".repeat(500), 1, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP);

                // 最后一条 UserMessage 是被截断后的用户请求（含环境上下文前缀）
                String userText = capturedPrompts.get(0).getInstructions().stream()
                                .filter(UserMessage.class::isInstance)
                                .map(UserMessage.class::cast)
                                .reduce((first, second) -> second)
                                .orElseThrow()
                                .getText();
                // 消息结构：环境上下文/编辑器版本前缀 + 头 50 字 + 截断标记 + 尾 50 字
                assertTrue(userText.contains("字".repeat(50) + "\n…[truncated]…\n" + "字".repeat(50)));
                assertTrue(userText.codePointCount(0, userText.length()) < 200);
        }

        @Test
        void guidesTerminalResultWhenSingleRoundToolsExceedTokenBudget() {
                AtomicInteger round = new AtomicInteger();
                List<Prompt> capturedPrompts = new ArrayList<>();
                ChatModel model = prompt -> {
                        capturedPrompts.add(prompt);
                        if (round.incrementAndGet() == 1) {
                                // 一轮内并行请求两个工具调用：最坏情况预检应直接拦截
                                AssistantMessage message = AssistantMessage.builder()
                                                .content("")
                                                .toolCalls(List.of(
                                                                new AssistantMessage.ToolCall("call-1", "function",
                                                                                "read_public_topic", "{\"topicId\":42}"),
                                                                new AssistantMessage.ToolCall("call-2", "function",
                                                                                "read_public_topic", "{\"topicId\":43}")))
                                                .build();
                                return new ChatResponse(List.of(new Generation(message)));
                        }
                        return response("{\"type\":\"QUESTION\",\"question\":\"Answered without tools\"}");
                };
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(
                                model, emptyTools(), ToolCallingManager.builder().build(),
                                new AgentRunBudgets(8, 1_000, 6_000, 8_000, 200),
                                Duration.ofSeconds(5));

                AgentQuestionResult result = assertInstanceOf(AgentQuestionResult.class, agent.run(
                                new AgentRunRequest("q", 3, List.of()),
                                new AgentCancellationToken(),
                                observer));

                // 两个 read 的最坏预估（2×8000+参数）远超 1000 token 预算 → 未执行任何工具
                assertEquals("Answered without tools", result.question());
                assertEquals(0, observer.completed.size());
                assertTrue(((UserMessage) capturedPrompts.get(1).getInstructions()
                                .get(capturedPrompts.get(1).getInstructions().size() - 1))
                                .getText().contains("tool budget"));
        }

        @Test
        void degradesRetryAlsoRecognizesAlternativeOverflowWording() {
                List<Prompt> capturedPrompts = new ArrayList<>();
                AtomicInteger calls = new AtomicInteger();
                ChatModel model = prompt -> {
                        capturedPrompts.add(prompt);
                        if (calls.incrementAndGet() == 1) {
                                throw new RuntimeException("prompt is too long: 90000 tokens > 65536 maximum");
                        }
                        return response("{\"type\":\"QUESTION\",\"question\":\"ok\"}");
                };
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentQuestionResult result = assertInstanceOf(AgentQuestionResult.class, agent.run(
                                new AgentRunRequest("q", 3, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP));

                assertEquals("ok", result.question());
                assertEquals(2, calls.get());
        }

        @Test
        void treatsAToolEncodedQuestionAsATerminalResult() {                ChatModel model = prompt -> toolCall(
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
        void treatsAToolEncodedAnswerAsATerminalResult() {
                ChatModel model = prompt -> toolCall(
                                "call-answer",
                                "answer",
                                "{\"answer\":\"论坛里暂时没有相关的探店分享。\",\"citations\":[]}");
                RecordingObserver observer = new RecordingObserver();
                ForumReActAgent agent = agent(model, emptyTools(), 8, Duration.ofSeconds(2));

                AgentAnswerResult result = assertInstanceOf(AgentAnswerResult.class, agent.run(
                                new AgentRunRequest("哪里有好吃的牛腩？", 3, List.of()),
                                new AgentCancellationToken(),
                                observer));

                assertEquals("论坛里暂时没有相关的探店分享。", result.answer());
                assertEquals(List.of(), result.citations());
                assertEquals(List.of(), observer.started);
        }

        @Test
        void rejectsAToolEncodedAnswerMixedWithRealToolCalls() {
                Deque<ChatResponse> responses = new ArrayDeque<>();
                responses.add(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                                .content("")
                                .toolCalls(List.of(
                                                new AssistantMessage.ToolCall("call-1", "function",
                                                                "search_similar_topics", "{\"query\":\"牛腩\"}"),
                                                new AssistantMessage.ToolCall("call-2", "function",
                                                                "ANSWER", "{\"answer\":\"编造\",\"citations\":[]}")))
                                .build()))));
                ForumReActAgent agent = agent(prompt -> responses.removeFirst(), emptyTools(), 8,
                                Duration.ofSeconds(2));

                AgentRunException exception = assertThrows(AgentRunException.class, () -> agent.run(
                                new AgentRunRequest("哪里有好吃的牛腩？", 3, List.of()),
                                new AgentCancellationToken(),
                                AgentRunObserver.NOOP));

                assertEquals(AgentRunFailure.INVALID_RESPONSE, exception.failure());
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

            // 第 9 次调用先触发「预算耗尽」引导（不再执行工具），模型（本测试的桩）仍要调用
            // → 第 10 次模型调用时硬失败。已执行的工具次数仍是 8。
            assertEquals(AgentRunFailure.TOOL_LIMIT, exception.failure());
            assertEquals(10, modelCalls.get());
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
                assertTrue(system.contains("Never reveal chain-of-thought"));
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
                                12_000,
                                timeout);
        }

        private ForumReActAgent agent(
                        ChatModel model,
                        ForumAuthoringTools tools,
                        ToolCallingManager toolCallingManager,
                        int maxToolCalls,
                        Duration timeout) {
                return agent(model, tools, toolCallingManager, maxToolCalls, 12_000, timeout);
        }

        private ForumReActAgent agent(
                        ChatModel model,
                        ForumAuthoringTools tools,
                        ToolCallingManager toolCallingManager,
                        int maxToolCalls,
                        int maxToolTokens,
                        Duration timeout) {
                return agent(model, tools, toolCallingManager, budgets(maxToolCalls, maxToolTokens), timeout);
        }

        private ForumReActAgent agent(
                        ChatModel model,
                        ForumAuthoringTools tools,
                        ToolCallingManager toolCallingManager,
                        AgentRunBudgets budgets,
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
                                budgets,
                                timeout);
        }

        /** 默认预算：用户请求 6000 字 / 读帖 8000 字 / 摘要 200 字（与生产默认一致）。 */
        private AgentRunBudgets budgets(int maxToolCalls, int maxToolTokens) {
                return new AgentRunBudgets(maxToolCalls, maxToolTokens, 6_000, 8_000, 200);
        }

        private ForumAuthoringTools emptyTools() {
                TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
                when(typeMapper.selectList(null)).thenReturn(List.of());
                return new ForumAuthoringTools(
                                typeMapper,
                                mock(TopicMapper.class),
                                new HybridTopicSearchService(query -> List.of(), query -> List.of()),
                                mock(ProhibitedUtils.class),
                                200,
                                8000);
        }

        private ForumAuthoringTools toolsReturningTopic(int topicId) {
                TopicSearchHit hit = new TopicSearchHit(topicId, "Previous guide", "Excerpt", 3, null);
                TopicTypeMapper typeMapper = mock(TopicTypeMapper.class);
                when(typeMapper.selectById(3)).thenReturn(new TopicType());
                return new ForumAuthoringTools(
                                typeMapper,
                                mock(TopicMapper.class),
                                new HybridTopicSearchService(query -> List.of(hit), query -> List.of(hit)),
                                mock(ProhibitedUtils.class),
                                200,
                                8000);
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
                private final List<String> deltas = new ArrayList<>();

                @Override
                public void toolStarted(String name, String arguments) {
                        started.add(name);
                }

                @Override
                public void toolCompleted(String name, String result) {
                        completed.add(name);
                }

                @Override
                public void onModelDelta(String text) {
                        deltas.add(text);
                }
        }
}
