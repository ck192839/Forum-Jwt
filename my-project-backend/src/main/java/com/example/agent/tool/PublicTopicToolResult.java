package com.example.agent.tool;

/**
 * read_public_topic 工具的结果：
 * - found ：帖子是否存在且公开（隐藏帖返回 false）
 * - topicId / title / topicTypeId：帖子基本信息
 * - bodyText ：从 Quill Delta 提取的纯文本正文（图片不包含）
 * notFound()：静态工厂，表示帖子不存在或不可见。
 */
public record PublicTopicToolResult(
        boolean found,
        Integer topicId,
        String title,
        Integer topicTypeId,
        String bodyText) {
    public static PublicTopicToolResult notFound() {
        return new PublicTopicToolResult(false, null, null, null, null);
    }
}
