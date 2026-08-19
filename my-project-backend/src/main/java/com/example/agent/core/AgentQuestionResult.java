package com.example.agent.core;

/**
 * 追问终态结果：Agent 发现缺少关键事实，向用户提一个问题。
 *
 * 触发条件（系统提示词约定）：关键信息缺失、明确未知或未决定时，
 * 在写草稿之前先 QUESTION；可选细节不追问，避免打扰用户。
 */
public record AgentQuestionResult(String question) implements AgentTerminalResult {
}
