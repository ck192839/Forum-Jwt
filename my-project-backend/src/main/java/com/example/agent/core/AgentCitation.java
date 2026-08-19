package com.example.agent.core;

/**
 * 一条引用（citation）：草稿里引用的历史帖子。
 * - topicId：帖子 id
 * - title ：帖子标题
 *
 * 安全约束：topicId 必须来自工具真实返回（knownTopicIds 白名单），
 * 由 AgentTerminalResultParser 强制校验，防止模型编造引用。
 */
public record AgentCitation(int topicId, String title) {
}
