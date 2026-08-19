package com.example.agent.session;

/**
 * 消息角色：
 * - SYSTEM ：系统提示（当前实现未持久化，保留扩展位）
 * - USER ：用户输入
 * - ASSISTANT ：助手输出（问题 / 「已生成草稿」提示等）
 * - TOOL ：工具结果（当前实现工具结果不进历史，保留扩展位）
 */
public enum AgentMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
