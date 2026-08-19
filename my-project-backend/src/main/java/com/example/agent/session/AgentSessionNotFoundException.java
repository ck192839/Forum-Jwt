package com.example.agent.session;

/**
 * 会话不存在异常：查询/操作不存在的、或不属于当前用户的会话时抛出。
 * 由 {@link com.example.agent.api.AgentControllerAdvice} 转成 HTTP 404。
 */
public class AgentSessionNotFoundException extends RuntimeException {
    public AgentSessionNotFoundException() {
        super("Agent session not found");
    }
}
