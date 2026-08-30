package com.example.agent.context;

import com.example.agent.session.AgentMessage;
import com.example.agent.session.AgentMessageRole;
import com.example.agent.session.AgentSession;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * prompt 历史组装器：在 token 预算内把「滚动摘要 + 近期逐字消息」组装成模型历史。
 *
 * 组装规则：
 * 1. 会话有摘要 → 作为一条 SystemMessage 放在最前（明确标注是早期对话的压缩摘要）
 * 2. 逐字消息只取 id 大于摘要覆盖点的部分（更早的已由摘要代表，不再重复携带）
 * 3. 从最新往最旧逐条纳入，直到触达 token 预算或近期窗口条数上限；
 *    放不下的旧消息直接丢弃——它们马上会被下一轮摘要任务覆盖
 *
 * 预算含义：maxContextTokens 覆盖「摘要 + 历史」两部分；系统提示、当前用户请求、
 * 模型输出与 run 内工具流量由固定余量（RESERVE_TOKENS）保证。
 */
public class AgentContextPlanner {
    // 预留给系统提示 + 当前用户请求的固定 token 余量（用户请求在 ForumReActAgent 出口
    // 截断到 maxUserMessageChars=6000 字，全中文 ≈ 6k token，加系统提示 ~2k 取 8k）
    private static final int RESERVE_TOKENS = 8_000;
    // 「压」的最小切片：剩余预算低于该值时不再保留截断消息（避免产生无意义的碎片段）
    private static final int MIN_SLICE_TOKENS = 200;
    // 摘要 SystemMessage 的固定包装文本（估算 token 时一并计入）
    private static final String SUMMARY_PREFIX =
            "Summary of the earlier conversation (older messages were compacted; "
                    + "the verbatim history below is more recent):\n";
    // 预算截断标记
    private static final String TRUNCATION_MARKER = "…[truncated]";
    // 截断标记自身的 token 余量（"…[truncated]" ≈ 4 token，留 8 保险）
    private static final int MARKER_TOKEN_ALLOWANCE = 8;

    private final int maxContextTokens; // 历史 + 摘要的总 token 预算
    private final int recentWindowMessages; // 逐字消息条数上限

    public AgentContextPlanner(int maxContextTokens, int recentWindowMessages) {
        if (maxContextTokens <= RESERVE_TOKENS) {
            throw new IllegalArgumentException("maxContextTokens must exceed the fixed reserve");
        }
        if (recentWindowMessages < 1) {
            throw new IllegalArgumentException("recentWindowMessages must be positive");
        }
        this.maxContextTokens = maxContextTokens;
        this.recentWindowMessages = recentWindowMessages;
    }

    /** 便捷构造：直接采用生产默认值（AgentContextProperties 的默认字段），避免魔法数字二次硬编码。 */
    public AgentContextPlanner(AgentContextProperties properties) {
        this(properties.getMaxContextTokens(), properties.getRecentWindowMessages());
    }

    /**
     * 组装历史消息。
     *
     * @param session 会话（读摘要与摘要覆盖点）
     * @param stored  该会话按 id 升序的全部存储消息
     */
    public List<Message> buildHistory(AgentSession session, List<AgentMessage> stored) {
        String summary = session == null ? null : session.getContextSummary();
        long summarizedMessageId = session == null || session.getSummarizedMessageId() == null
                ? 0L
                : session.getSummarizedMessageId();

        // 预算 = 总预算 - 固定余量 - 摘要开销；摘要先占位，剩下的给逐字消息
        int budget = maxContextTokens - RESERVE_TOKENS;
        ArrayDeque<Message> verbatim = new ArrayDeque<>(); // 逐字消息（addFirst 恢复时间顺序）
        if (summary != null && !summary.isBlank()) {
            String summaryText = SUMMARY_PREFIX + summary;
            budget -= ContextTokenEstimator.estimateTokens(summaryText);
        }

        // 从最新往最旧纳入逐字消息：id > 覆盖点、跳过非对话角色、预算/条数双上限。
        // 「压」而非「丢」：最新一条对话消息若整体放不下，按剩余预算截断头部保留
        // （估算器保证 1 字符 ≤ 1 token，截到剩余预算个字符必不超预算），
        // 避免单条超长消息导致逐字历史清零。
        int used = 0;
        int kept = 0;
        boolean newest = true;
        for (int index = stored.size() - 1; index >= 0; index--) {
            AgentMessage storedMessage = stored.get(index);
            if (storedMessage.getId() != null && storedMessage.getId() <= summarizedMessageId) {
                break; // 更早的都已被摘要覆盖（消息按 id 升序，可直接停）
            }
            Message message = toSpringMessage(storedMessage);
            if (message == null) {
                continue;
            }
            if (kept >= recentWindowMessages) {
                break;
            }
            int tokens = ContextTokenEstimator.estimateTokens(storedMessage.getContent());
            if (used + tokens > budget) {
                int remaining = budget - used;
                if (newest && remaining >= MIN_SLICE_TOKENS) {
                    // 扣除截断标记自身的 token 余量，保证截断后仍不超预算
                    verbatim.addFirst(toTruncatedMessage(storedMessage, remaining - MARKER_TOKEN_ALLOWANCE));
                    used += remaining;
                    kept++;
                }
                break; // 预算放不下 → 更早的更放不下，直接停
            }
            verbatim.addFirst(message); // 保持时间顺序
            used += tokens;
            kept++;
            newest = false;
        }

        // 最终顺序：摘要在前，逐字消息在后
        List<Message> history = new ArrayList<>(verbatim.size() + 1);
        if (summary != null && !summary.isBlank()) {
            history.add(new SystemMessage(SUMMARY_PREFIX + summary));
        }
        history.addAll(verbatim);
        return List.copyOf(history);
    }

    /** 存储消息 → Spring AI Message（只保留 USER/ASSISTANT，与旧 toHistory 行为一致）。 */
    private Message toSpringMessage(AgentMessage stored) {
        if (stored.getRole() == AgentMessageRole.USER) {
            return new UserMessage(stored.getContent());
        }
        if (stored.getRole() == AgentMessageRole.ASSISTANT) {
            return new AssistantMessage(stored.getContent());
        }
        return null;
    }

    /** 按角色构造「头部截断到 maxChars 个字符」的消息（预算压缩用，存储层不动）。 */
    private Message toTruncatedMessage(AgentMessage stored, int maxChars) {
        String content = TextTruncation.truncateHead(stored.getContent(), maxChars, TRUNCATION_MARKER);
        return stored.getRole() == AgentMessageRole.USER
                ? new UserMessage(content)
                : new AssistantMessage(content);
    }
}
