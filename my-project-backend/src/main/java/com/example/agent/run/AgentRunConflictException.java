package com.example.agent.run;

/**
 * 会话运行冲突异常：同一会话同时只能有一个 run 在跑。
 *
 * 触发：AgentRunService.start 里 sessionRuns.putIfAbsent 发现已有 run 时抛出。
 * 由 {@link com.example.agent.api.AgentControllerAdvice} 转成 HTTP 409 Conflict。
 */
public class AgentRunConflictException extends RuntimeException {
    public AgentRunConflictException() {
        super("An Agent run is already active for this session");
    }
}
