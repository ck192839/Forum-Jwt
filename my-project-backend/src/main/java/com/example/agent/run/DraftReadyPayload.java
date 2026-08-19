package com.example.agent.run;

import com.example.agent.core.AgentCitation;

import java.util.List;

/**
 * draft_ready 事件负载：草稿就绪，前端据此展示草稿卡片并可「应用到编辑器」。
 *
 * - draftVersion ：草稿自身版本号（同会话每次生成递增，落库后返回）
 * - basedOnEditorVersion ：草稿基于的编辑器版本（防过期覆盖）
 * - targetEditorId ：草稿应被应用到的编辑器 id（null = 新建帖）
 */
public record DraftReadyPayload(
        String title,
        int topicTypeId,
        String bodyMarkdown,
        List<AgentCitation> citations,
        int draftVersion,
        int basedOnEditorVersion,
        String targetEditorId) {
    public DraftReadyPayload {
        citations = List.copyOf(citations);
    }
}
