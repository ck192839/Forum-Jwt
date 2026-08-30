package com.example.agent.run;

/**
 * context_notice 事件载荷：Agent run 内触发了上下文降级（溢出裁剪 / 工具预算耗尽），
 * 文案说明降级原因与对回答完整性的潜在影响。事件照常落库，会话恢复时重放。
 */
public record ContextNoticePayload(String text) {
}
