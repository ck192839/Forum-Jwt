package com.example.agent.session;

import java.util.List;

/**
 * 会话聚合根：一次查询把会话 + 全部消息 + 全部事件 + 最新草稿打包返回。
 * 用于「恢复会话」接口，避免多次往返数据库。
 */
public record AgentSessionAggregate(
                AgentSession session, // 会话本体
                List<AgentMessage> messages, // 全部消息（按 id 升序）
                List<AgentEvent> events, // 全部事件（按 id 升序，可重放）
                AgentDraft draft // 最新草稿（可能为 null）
) {
}
