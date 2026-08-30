package com.example.agent.run;

/**
 * answer 事件的 payload：Agent 对论坛问答的正式回答。
 *
 * - answer ：回答正文（Markdown 文本）
 * 引用出处不放在本 payload 里，而是先逐条推 citation 事件（与草稿引用同机制），
 * 前端按 topicId 去重后渲染成可跳转的出处列表。
 */
public record AnswerPayload(String answer) {
}
