package com.example.agent.tool;

/**
 * list_topic_types 工具的单条结果：论坛的一个板块。
 * - id ：板块 id（validate_draft 的 topicTypeId 必须合法）
 * - name ：板块名
 * - description ：板块描述
 */
public record TopicTypeToolResult(int id, String name, String description) {
}
