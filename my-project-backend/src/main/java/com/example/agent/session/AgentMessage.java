package com.example.agent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

/**
 * 聊天消息实体（表 agent_message）。
 * 属于某个会话（session_id 外键，删除会话时级联删除）。
 */
@Data
@TableName("agent_message")
public class AgentMessage {
    @TableId(type = IdType.AUTO)
    private Long id; // 主键
    private Long sessionId; // 所属会话 id
    private AgentMessageRole role; // 消息角色（USER / ASSISTANT）
    private String content; // 消息内容
    private Timestamp createdAt; // 创建时间（按 id 升序即时间顺序）
}
