package com.example.agent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

/**
 * Agent 会话实体（表 agent_session）。
 * 一个会话 = 一次「与 Agent 的持续对话」，包含消息、事件、草稿。
 * - uid ：归属用户（多租户隔离）
 * - status ：ACTIVE / COMPLETED / FAILED
 * - expiresAt ：过期时间（默认 30 天，由 AgentSessionCleanupJob 定时清理）
 * MyBatis-Plus 注解映射表名与主键自增策略。
 */
@Data
@TableName("agent_session")
public class AgentSession {
    @TableId(type = IdType.AUTO)
    private Long id; // 主键
    private Integer uid; // 归属用户 id
    private AgentSessionStatus status; // 会话状态
    private Timestamp createdAt; // 创建时间
    private Timestamp updatedAt; // 最后活动时间（列表按此排序）
    private Timestamp expiresAt; // 过期时间
    private String contextSummary; // 旧消息的滚动摘要（null 表示尚无摘要）
    private Long summarizedMessageId; // 摘要覆盖到的最后一条消息 id（0 = 尚未覆盖）
}
