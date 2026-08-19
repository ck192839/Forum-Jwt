package com.example.agent.session;

/**
 * 会话状态：
 * - ACTIVE ：进行中（可继续对话，前端自动选中 ACTIVE 会话）
 * - COMPLETED：已结束（正常完成）
 * - FAILED ：失败
 */
public enum AgentSessionStatus {
    ACTIVE,
    COMPLETED,
    FAILED
}
