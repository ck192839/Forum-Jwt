package com.example.agent.core;

/**
 * Agent 的「终态结果」：一次 run 只能以三种方式结束。
 * - {@link AgentQuestionResult}：向用户追问缺失信息
 * - {@link AgentAnswerResult} ：对论坛问答的正式回答（带白名单引用）
 * - {@link AgentDraftResult} ：产出校验过的草稿
 *
 * 使用 sealed 接口（Java 17+）：编译器保证只有这三个子类型，
 * 外部无法新增类型，switch 模式匹配可以穷尽且不会漏分支。
 */
public sealed interface AgentTerminalResult permits AgentQuestionResult, AgentAnswerResult, AgentDraftResult {
}
