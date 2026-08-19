package com.example.agent.core;

/**
 * Agent 运行期的统一异常，携带失败分类 {@link AgentRunFailure}。
 *
 * 设计要点：整个 Agent 模块内部只抛这一种受控异常，
 * 上层（AgentRunService.handleFailure）根据 failure 分类决定：
 * - 推什么 SSE 错误事件
 * - run_completed 的状态（CANCELLED 或 FAILED）
 * - 返回给用户的安全文案
 * 避免上层到处 catch 各种底层异常。
 */
public class AgentRunException extends RuntimeException {
    private final AgentRunFailure failure;

    public AgentRunException(AgentRunFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AgentRunException(AgentRunFailure failure, String message, Throwable cause) {
        super(message, cause);
        this.failure = failure;
    }

    /** 获取失败分类。 */
    public AgentRunFailure failure() {
        return failure;
    }
}
