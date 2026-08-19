package com.example.agent.run;

/**
 * citation 事件负载：一条引用帖子（SSE 推送给前端展示引用链接）。
 */
public record CitationPayload(int topicId, String title) {
}
