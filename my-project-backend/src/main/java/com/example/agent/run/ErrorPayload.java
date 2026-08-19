package com.example.agent.run;

/**
 * error 事件负载：运行出错通知。
 *
 * - runId ：出错的那次运行 id
 * - code ：失败分类（CANCELLED / TIMEOUT / TOOL_LIMIT / INVALID_RESPONSE / EXECUTION）
 * - message ：面向用户的安全文案（不泄露内部细节）
 * - retryable：是否可重试（前端据此决定是否提示「重试」）
 */
public record ErrorPayload(String runId, String code, String message, boolean retryable) {
}
