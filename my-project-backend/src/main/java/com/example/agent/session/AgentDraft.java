package com.example.agent.session;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

@Data
@TableName("agent_draft")
public class AgentDraft {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private Integer version;
    private Integer editorVersion;
    private String targetEditorId;
    private String title;
    private Integer topicTypeId;
    private String bodyMarkdown;
    private String citationsJson;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
