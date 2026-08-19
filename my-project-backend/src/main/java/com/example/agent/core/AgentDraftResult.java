package com.example.agent.core;

import java.util.List;

/**
 * 草稿终态结果：Agent 完成一篇帖子的草稿。
 *
 * - title ：标题（1-30 字）
 * - topicTypeId ：板块 id
 * - bodyMarkdown ：Markdown 正文（1-20000 字）
 * - citations ：引用的历史帖子（≤ 6 条）
 * - basedOnEditorVersion：草稿基于的编辑器版本（用于防过期覆盖）
 *
 * 注意：这些字段在大多数情况下来自「validate_draft 校验通过的那次参数」，
 * 即 Agent 不能自由发挥正文，必须先过校验（见 ForumReActAgent）。
 * citations 构造时不可变拷贝。
 */
public record AgentDraftResult(
        String title,
        int topicTypeId,
        String bodyMarkdown,
        List<AgentCitation> citations,
        int basedOnEditorVersion) implements AgentTerminalResult {
    public AgentDraftResult {
        citations = List.copyOf(citations);
    }
}
