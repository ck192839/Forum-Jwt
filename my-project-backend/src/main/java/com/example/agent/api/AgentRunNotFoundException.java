package com.example.agent.api;

/**
 * 找不到指定的 Agent 运行（run）时抛出的异常。
 *
 * 场景：用户尝试取消一个不存在、或不属于自己的 runId 时（{@link AgentController#cancelRun}），
 * 由 {@link AgentControllerAdvice} 统一捕获并转成 HTTP 404。
 * 包级私有（无 public）：它是 Agent API 层内部使用的异常，不对外暴露。
 */
final class AgentRunNotFoundException extends RuntimeException {
}
