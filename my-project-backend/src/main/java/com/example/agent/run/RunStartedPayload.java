package com.example.agent.run;

/**
 * run_started 事件负载：运行开始。前端用它拿到 runId（取消时用）和 sessionId。
 */
public record RunStartedPayload(String runId, long sessionId) {
}
