package com.example.agent.session;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentDraftMapper extends BaseMapper<AgentDraft> {
    @Select("""
            SELECT id, session_id, version, editor_version, target_editor_id, title, topic_type_id,
                   body_markdown, citations_json, created_at, updated_at
            FROM agent_draft
            WHERE session_id = #{sessionId}
            """)
    AgentDraft selectBySessionId(@Param("sessionId") long sessionId);

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
