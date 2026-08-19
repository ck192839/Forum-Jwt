package com.example.agent.core;

/**
 * Agent 运行失败原因的分类枚举。
 *
 * 每种失败类型对应：
 * - 一个安全提示文案（AgentRunService.safeMessage，不暴露内部细节）
 * - 一个 SSE 状态（run_completed 的 status：CANCELLED / FAILED）
 *
 * - CANCELLED ：用户取消或线程中断
 * - TIMEOUT ：超过 60s 总预算
 * - TOOL_LIMIT ：超过 8 次工具调用上限
 * - EXECUTION ：模型/工具执行抛出的未知异常
 * - INVALID_RESPONSE：模型输出了无法解析/未校验通过的内容
 */
public enum AgentRunFailure {
    CANCELLED,
    TIMEOUT,
    TOOL_LIMIT,
    EXECUTION,
    INVALID_RESPONSE
}
