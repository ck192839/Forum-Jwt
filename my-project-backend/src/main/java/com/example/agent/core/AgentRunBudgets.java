package com.example.agent.core;

/**
 * 单次 run 的资源预算（不可变）。由装配层从配置（agent.execution.* / agent.context.*）组装，
 * ForumReActAgent 只依赖这份值对象，不感知配置类。
 *
 * - maxToolCalls        ：run 内工具调用次数上限
 * - maxToolTokens       ：工具结果 + 调用参数累计 token 预算（含执行前最坏情况预估）
 * - maxUserMessageChars ：当前用户请求的字符上限（头尾保留截断，防超长粘贴撑爆窗口）
 * - readTopicMaxChars   ：read_public_topic 单次结果的字符上界（最坏情况预估用）
 * - excerptMaxChars     ：search_similar_topics 单条摘要的字符上界（最坏情况预估用）
 */
public record AgentRunBudgets(
                int maxToolCalls,
                int maxToolTokens,
                int maxUserMessageChars,
                int readTopicMaxChars,
                int excerptMaxChars) {

    public AgentRunBudgets {
        if (maxToolCalls < 1 || maxToolTokens < 1 || maxUserMessageChars < 1
                        || readTopicMaxChars < 1 || excerptMaxChars < 1) {
            throw new IllegalArgumentException("all budgets must be positive");
        }
    }
}
