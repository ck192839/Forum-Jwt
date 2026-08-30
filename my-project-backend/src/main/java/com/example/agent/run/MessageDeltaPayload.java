package com.example.agent.run;

/**
 * message_delta 事件负载：模型流式输出的「可见文本」增量。
 * 后端从流式 chunk 中增量解码终态 JSON 的正文（answer/question/bodyMarkdown），
 * 按时间窗节流后推送；前端追加到 streamingText 渲染打字机效果，
 * 终态事件（question/answer/draft_ready）到达时清空并展示完整消息。
 */
public record MessageDeltaPayload(String text) {
}
