package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 草稿表 Mapper。
 * 关键：upsert 用 MySQL 的 ON DUPLICATE KEY UPDATE 实现「每会话一份、重复生成时版本自增」。
 * 表结构：agent_draft(id, session_id, version, editor_version, target_editor_id,
 * title,
 * topic_type_id, body_markdown, citations_json, created_at, updated_at)
 * 唯一索引：uk_agent_draft_session(session_id)
 */
@Mapper
public interface AgentDraftMapper extends BaseMapper<AgentDraft> {
    /** 按会话查草稿（最多一份，可能为 null）。 */
    @Select("""
            SELECT id, session_id, version, editor_version, target_editor_id, title, topic_type_id,
                   body_markdown, citations_json, created_at, updated_at
            FROM agent_draft
            WHERE session_id = #{sessionId}
            """)
    AgentDraft selectBySessionId(@Param("sessionId") long sessionId);

    /**
     * 插入或更新草稿：
     * - 新会话 → 插入 version=1
     * - 已有草稿（命中唯一索引）→ 更新所有内容字段，version 在库内 +1
     * useGeneratedKeys：回填自增主键。
     */
    @Insert("""
            INSERT INTO agent_draft (
                session_id, version, editor_version, target_editor_id, title, topic_type_id,
                body_markdown, citations_json, created_at, updated_at
            ) VALUES (
                #{sessionId}, #{version}, #{editorVersion}, #{targetEditorId}, #{title}, #{topicTypeId},
                #{bodyMarkdown}, #{citationsJson}, #{createdAt}, #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                version = agent_draft.version + 1,
                editor_version = VALUES(editor_version),
                target_editor_id = VALUES(target_editor_id),
                title = VALUES(title),
                topic_type_id = VALUES(topic_type_id),
                body_markdown = VALUES(body_markdown),
                citations_json = VALUES(citations_json),
                updated_at = VALUES(updated_at)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(AgentDraft draft);
}
