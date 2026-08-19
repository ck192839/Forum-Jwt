package com.example.agent.core;

/**
 * 「模型输出未通过校验」异常：与 AgentRunException 的区别是——
 * 这是可修复的错误（模型可以重试），而 AgentRunException 是终局错误。
 *
 * 触发场景：
 * - 输出不是合法 JSON / 字段缺失或多余（AgentTerminalResultParser）
 * - 草稿没经过 validate_draft
 * - 引用不在工具返回的白名单里
 * - 基于的编辑器版本已过期
 *
 * ForumReActAgent 捕获它后会发「修复提示」让模型重试（最多 2 次），
 * 重试耗尽才转成 AgentRunException(INVALID_RESPONSE) 终局失败。
 */
public class AgentOutputValidationException extends RuntimeException {
    public AgentOutputValidationException(String message) {
        super(message);
    }

    public AgentOutputValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
