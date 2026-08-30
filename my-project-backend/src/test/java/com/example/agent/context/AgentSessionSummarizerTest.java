package com.example.agent.context;

import com.example.agent.session.AgentMessage;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSession;
import com.example.agent.session.AgentSessionMapper;
import com.example.agent.session.AgentSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSessionSummarizerTest {
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void shutDownExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void skipsSummarizationWhenUnsummarizedMessagesAreFew() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        // 未摘要消息 20 条 + 余量 4 = 24 → 恰好不触发
        when(sessionMapper.selectOwnedById(99L, 7)).thenReturn(session(null, 0L));
        when(sessionService.messagesAfter(99L, 0L)).thenReturn(messages(24, 0));
        AgentSessionSummarizer summarizer = summarizer(sessionMapper, sessionService, callableModel("s"));

        summarizer.summarizeOnce(7, 99L);

        verify(sessionService, never()).updateContextSummary(anyInt(), anyLong(), any(), anyLong());
    }

    @Test
    void mergesPreviousSummaryWithOldMessagesAndAdvancesCoverage() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        // 未摘要 26 条 → 除最近 20 条外，前 6 条进入摘要；覆盖点 = 第 6 条的 id（起点 0 → id 6）
        when(sessionMapper.selectOwnedById(99L, 7)).thenReturn(session("prior summary", 0L));
        when(sessionService.messagesAfter(99L, 0L)).thenReturn(messages(26, 0));
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(chatResponse("merged new summary"));
        AgentSessionSummarizer summarizer = summarizer(sessionMapper, sessionService, model);

        summarizer.summarizeOnce(7, 99L);

        verify(sessionService).updateContextSummary(7, 99L, "merged new summary", 6L);
    }

    @Test
    void keepsTheRecentWindowVerbatimOutgoingSummaryInput() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        List<AgentMessage> unsummarized = messages(26, 0);
        when(sessionMapper.selectOwnedById(99L, 7)).thenReturn(session(null, 0L));
        when(sessionService.messagesAfter(99L, 0L)).thenReturn(unsummarized);
        List<Prompt> captured = new ArrayList<>();
        ChatModel model = prompt -> {
            captured.add(prompt);
            return chatResponse("summary");
        };
        AgentSessionSummarizer summarizer = summarizer(sessionMapper, sessionService, model);

        assertTrue(summarizer.summarizeOnce(7, 99L));

        // 转录只包含被摘要的 6 条（id 1-6），不含近期窗口（id 7-26）
        String userText = captured.get(0).getInstructions().get(1).getText();
        assertEquals(6, userText.split("User:|Assistant:").length - 1);
        assertTrue(userText.contains("message 6"));
        assertFalse(userText.contains("message 20"));
    }

    @Test
    void silentlyIgnoresModelFailureOrEmptyOutput() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        when(sessionMapper.selectOwnedById(99L, 7)).thenReturn(session(null, 0L));
        when(sessionService.messagesAfter(99L, 0L)).thenReturn(messages(30, 0));
        // 模型抛异常 → 摘要器吞掉、返回失败，不落库、不向上抛
        ChatModel failing = prompt -> {
            throw new RuntimeException("deepseek down");
        };
        assertFalse(summarizer(sessionMapper, sessionService, failing).summarizeOnce(7, 99L));
        verify(sessionService, never()).updateContextSummary(anyInt(), anyLong(), any(), anyLong());

        // 模型返回空 → 同样视为失败并跳过
        ChatModel empty = prompt -> chatResponse("  ");
        assertFalse(summarizer(sessionMapper, sessionService, empty).summarizeOnce(7, 99L));
        verify(sessionService, never()).updateContextSummary(anyInt(), anyLong(), any(), anyLong());
    }

    @Test
    void scheduleRunsAsynchronouslyWithoutThrowing() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        when(sessionMapper.selectOwnedById(99L, 7)).thenThrow(new RuntimeException("db down"));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        executors.add(worker);
        AgentSessionSummarizer summarizer = new AgentSessionSummarizer(
                sessionMapper, sessionService, callableModel("s"), worker, 20, 4, 4000, 5);

        summarizer.schedule(7, 99L); // 异步失败必须被吞掉，不向上抛
    }

    @Test
    void serializesConcurrentSchedulesForTheSameSession() throws Exception {
        // 两线程池 + 同会话两个任务：per-session 锁应保证关键段串行（最大并发 = 1）
        java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxInFlight = new java.util.concurrent.atomic.AtomicInteger();
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService sessionService = mock(AgentSessionService.class);
        when(sessionMapper.selectOwnedById(99L, 7)).thenAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return session(null, 0L);
        });
        when(sessionService.messagesAfter(anyLong(), anyLong())).thenReturn(List.of());
        ExecutorService worker = Executors.newFixedThreadPool(2);
        executors.add(worker);
        AgentSessionSummarizer summarizer = new AgentSessionSummarizer(
                sessionMapper, sessionService, callableModel("s"), worker, 20, 4, 4000, 5);

        summarizer.schedule(7, 99L);
        summarizer.schedule(7, 99L);
        worker.shutdown();
        org.junit.jupiter.api.Assertions.assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, maxInFlight.get());
    }

    private AgentSessionSummarizer summarizer(
            AgentSessionMapper sessionMapper,
            AgentSessionService sessionService,
            ChatModel model) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        executors.add(worker);
        return new AgentSessionSummarizer(
                sessionMapper, sessionService, model, worker, 20, 4, 4000, 5);
    }

    private ChatModel callableModel(String text) {
        return prompt -> chatResponse(text);
    }

    private ChatResponse chatResponse(String text) {
        return new ChatResponse(List.of(new Generation(
                org.springframework.ai.chat.messages.AssistantMessage.builder().content(text).build())));
    }

    private AgentSession session(String summary, Long summarizedMessageId) {
        AgentSession session = new AgentSession();
        session.setId(99L);
        session.setUid(7);
        session.setContextSummary(summary);
        session.setSummarizedMessageId(summarizedMessageId);
        return session;
    }

    /** 生成 count 条消息，id 从 startId 起连续递增（角色 USER/ASSISTANT 交替）。 */
    private List<AgentMessage> messages(int count, long startId) {
        List<AgentMessage> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            AgentMessage message = new AgentMessage();
            message.setId(startId + index + 1);
            message.setSessionId(99L);
            message.setRole(index % 2 == 0 ? AgentMessageRole.USER : AgentMessageRole.ASSISTANT);
            message.setContent("message " + (startId + index + 1));
            result.add(message);
        }
        return result;
    }
}
