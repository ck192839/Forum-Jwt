package com.example.agent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

/**
 * 草稿实体（表 agent_draft）。每个会话最多一份（uk_agent_draft_session 唯一索引），
 * 再次生成草稿时通过 upsert 覆盖并让 version 自增。
 * - version ：草稿自身版本（每次生成 +1，前端展示「草稿 vN」）
 * - editorVersion ：草稿基于的编辑器版本（防过期覆盖）
 * - targetEditorId ：草稿应被应用到的编辑器 id（null = 新建帖）
 * - citationsJson ：引用帖子 JSON 数组（存储为字符串）
 */
@Data
@TableName("agent_draft")
public class AgentDraft {
    @TableId(type = IdType.AUTO)
    private Long id; // 主键
    private Long sessionId; // 所属会话 id
    private Integer version; // 草稿版本（自增）
    private Integer editorVersion; // 基于的编辑器版本
    private String targetEditorId; // 目标编辑器 id
    private String title; // 标题
    private Integer topicTypeId; // 板块 id
    private String bodyMarkdown; // Markdown 正文
    private String citationsJson; // 引用 JSON
    private Timestamp createdAt; // 创建时间
    private Timestamp updatedAt; // 更新时间
}
