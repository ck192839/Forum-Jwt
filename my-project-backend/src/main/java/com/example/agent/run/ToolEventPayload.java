package com.example.agent.run;

/**
 * tool_started / tool_completed 事件负载：工具执行状态变化。
 * 前端据此更新工具时间线（“执行中 → 已完成”）。
 */
public record ToolEventPayload(String runId, String toolName) {
}
