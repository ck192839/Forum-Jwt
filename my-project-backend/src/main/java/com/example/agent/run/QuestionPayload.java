package com.example.agent.run;

/**
 * question 事件负载：Agent 向用户追问。
 *
 * - targetEditorId ：追问关联的编辑器 id（用于后续回复时保持同一编辑上下文）
 * - basedOnEditorVersion：对应的编辑器版本
 * 便捷构造器：纯聊天场景下无编辑器上下文（editorId=null，version=0）。
 */
public record QuestionPayload(
        String question,
        String targetEditorId,
        int basedOnEditorVersion) {
    public QuestionPayload(String question) {
        this(question, null, 0);
    }
}
