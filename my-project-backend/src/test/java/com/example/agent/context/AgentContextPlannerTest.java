package com.example.agent.context;

import com.example.agent.session.AgentMessage;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextPlannerTest {

    @Test
    void carriesAllRecentMessagesInOrderWhenWithinBudget() {
        AgentSession session = session(null, 0L);
        List<AgentMessage> stored = List.of(
                storedMessage(1, AgentMessageRole.USER, "first question"),
                storedMessage(2, AgentMessageRole.ASSISTANT, "first answer"),
                storedMessage(3, AgentMessageRole.USER, "follow up"));
        AgentContextPlanner planner = new AgentContextPlanner(48_000, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(3, history.size());
        assertInstanceOf(UserMessage.class, history.get(0));
        assertInstanceOf(AssistantMessage.class, history.get(1));
        assertInstanceOf(UserMessage.class, history.get(2));
        assertTrue(((UserMessage) history.get(2)).getText().contains("follow up"));
    }

    @Test
    void injectsPersistedSummaryAsLeadingSystemMessage() {
        AgentSession session = session("User asked about noodle shops; assistant recommended 东镇大街.", 2L);
        List<AgentMessage> stored = List.of(
                storedMessage(3, AgentMessageRole.USER, "any cheaper options?"),
                storedMessage(4, AgentMessageRole.ASSISTANT, "Try the food street."));
        AgentContextPlanner planner = new AgentContextPlanner(48_000, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(3, history.size());
        SystemMessage summary = assertInstanceOf(SystemMessage.class, history.get(0));
        assertTrue(summary.getText().contains("noodle shops"));
        assertTrue(summary.getText().contains("东镇大街"));
    }

    @Test
    void skipsMessagesAlreadyCoveredByTheSummary() {
        AgentSession session = session("summary text", 2L);
        List<AgentMessage> stored = List.of(
                storedMessage(1, AgentMessageRole.USER, "covered by summary"),
                storedMessage(2, AgentMessageRole.ASSISTANT, "also covered"),
                storedMessage(3, AgentMessageRole.USER, "verbatim recent"));
        AgentContextPlanner planner = new AgentContextPlanner(48_000, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(2, history.size()); // 摘要 + 仅 1 条逐字消息
        String last = ((UserMessage) history.get(1)).getText();
        assertTrue(last.contains("verbatim recent"));
    }

    @Test
    void dropsOldestMessagesWhenExceedingTheRecentWindowCap() {
        AgentSession session = session(null, 0L);
        List<AgentMessage> stored = new ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            stored.add(storedMessage(index, AgentMessageRole.USER, "message " + index));
        }
        AgentContextPlanner planner = new AgentContextPlanner(48_000, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(20, history.size());
        assertTrue(((UserMessage) history.get(0)).getText().contains("message 11"));
        assertTrue(((UserMessage) history.get(19)).getText().contains("message 30"));
    }

    @Test
    void dropsOldestMessagesWhenExceedingTheTokenBudget() {
        AgentSession session = session(null, 0L);
        // 每条 300 CJK 字 ≈ 300 token；总预算 8600，扣除 8000 固定预留后剩 600
        List<AgentMessage> stored = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            stored.add(storedMessage(index, AgentMessageRole.USER, "字".repeat(300)));
        }
        AgentContextPlanner planner = new AgentContextPlanner(8_600, 20);

        List<Message> history = planner.buildHistory(session, stored);

        // 8600 - 8000(预留) = 600 token 可用 → 只放得下 2 条（各 300 字）
        assertEquals(2, history.size());
        assertEquals("字".repeat(300), ((UserMessage) history.get(1)).getText());
    }

    @Test
    void skipsNonDialogRoles() {
        AgentSession session = session(null, 0L);
        List<AgentMessage> stored = List.of(
                storedMessage(1, AgentMessageRole.TOOL, "{\"raw\":true}"),
                storedMessage(2, AgentMessageRole.USER, "real message"));
        AgentContextPlanner planner = new AgentContextPlanner(48_000, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(1, history.size());
        assertInstanceOf(UserMessage.class, history.get(0));
    }

    @Test
    void keepsTruncatedHeadWhenNewestMessageAloneExceedsBudget() {
        AgentSession session = session(null, 0L);
        // 最新一条 1000 CJK 字（1000 token）远超剩余预算：应截断头部保留而非清空历史
        // 预算 8600 - 8000 预留 = 600 token
        List<AgentMessage> stored = List.of(
                storedMessage(1, AgentMessageRole.USER, "字".repeat(1000)));
        AgentContextPlanner planner = new AgentContextPlanner(8_600, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(1, history.size());
        String text = ((UserMessage) history.get(0)).getText();
        // 600 token 预算 - 8 标记余量 = 592 字 + 截断标记
        assertTrue(text.startsWith("字".repeat(100)));
        assertTrue(text.endsWith("…[truncated]"));
        assertTrue(ContextTokenEstimator.estimateTokens(text) <= 600);
    }

    @Test
    void producesNoFragmentWhenRemainingBudgetIsBelowMinimumSlice() {
        AgentSession session = session(null, 0L);
        // 剩余预算只有 100 token（< 最小切片 200）→ 不产生无意义碎片段，逐字历史为空
        List<AgentMessage> stored = List.of(
                storedMessage(1, AgentMessageRole.USER, "字".repeat(500)));
        AgentContextPlanner planner = new AgentContextPlanner(8_100, 20);

        List<Message> history = planner.buildHistory(session, stored);

        assertEquals(0, history.size());
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        assertThrows(IllegalArgumentException.class, () -> new AgentContextPlanner(1000, 20));
        assertThrows(IllegalArgumentException.class, () -> new AgentContextPlanner(48_000, 0));
    }

    private AgentSession session(String summary, Long summarizedMessageId) {
        AgentSession session = new AgentSession();
        session.setId(99L);
        session.setUid(7);
        session.setContextSummary(summary);
        session.setSummarizedMessageId(summarizedMessageId);
        return session;
    }

    private AgentMessage storedMessage(long id, AgentMessageRole role, String content) {
        AgentMessage message = new AgentMessage();
        message.setId(id);
        message.setSessionId(99L);
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
