package com.example.agent.session;

/**
 * 保存草稿的输入参数（与 AgentDraft 实体解耦，避免把实体直接传给服务层）。
 * targetEditorId 为 null 表示草稿用于新建帖。
 */
public record AgentDraftInput(
        int editorVersion, // 基于的编辑器版本
        String title, // 标题
        int topicTypeId, // 板块 id
        String bodyMarkdown, // Markdown 正文
        String citationsJson, // 引用 JSON 字符串
        String targetEditorId // 目标编辑器 id（可 null）
) {
    /** 便捷构造器：不携带编辑器上下文（纯聊天会话的草稿）。 */
    public AgentDraftInput(
            int editorVersion,
            String title,
            int topicTypeId,
            String bodyMarkdown,
            String citationsJson) {
        this(editorVersion, title, topicTypeId, bodyMarkdown, citationsJson, null);
    }
}
