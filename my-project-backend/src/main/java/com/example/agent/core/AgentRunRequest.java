package com.example.agent.core;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 一次 Agent 运行的请求参数（不可变 record）。
 *
 * - userMessage ：用户本次输入的自然语言（必填，非空）
 * - editorVersion ：发起时编辑器的版本号（用于草稿防过期校验，必填 ≥ 0）
 * - history ：会话历史消息（Spring AI 的 Message 列表），可为 null 表示无历史
 * - context ：环境上下文（当前时间/天气摘要），可为 null 表示无
 *
 * 构造器里做防御性校验：参数不合法直接拒绝（fail-fast），
 * 历史列表用 List.copyOf 拷贝，防止外部修改。
 */
public record AgentRunRequest(
        String userMessage,
        int editorVersion,
        List<Message> history,
        AgentRunContext context) {
    /** 兼容构造器：不携带环境上下文（时间/天气注入是可选能力）。 */
    public AgentRunRequest(String userMessage, int editorVersion, List<Message> history) {
        this(userMessage, editorVersion, history, null);
    }

    public AgentRunRequest {
        // 用户输入不能为空
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("userMessage is required");
        }
        // 编辑器版本号不能为负
        if (editorVersion < 0) {
            throw new IllegalArgumentException("editorVersion must be non-negative");
        }
        // null 历史归一化为空列表，并做不可变拷贝
        history = history == null ? List.of() : List.copyOf(history);
    }
}
