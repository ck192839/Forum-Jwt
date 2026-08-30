package com.example.agent.run;

/**
 * 一次 Agent 运行的命令参数（由 AgentController 从 RunRequest 转换而来）。
 *
 * 字段说明：
 * - message ：用户输入（可为 null，但此时必须有编辑器草稿）
 * - editorVersion ：发起时编辑器版本号（防过期，必填 ≥ 0）
 * - editorTitle / editorTopicTypeId / editorBodyMarkdown：编辑器现有内容（AI 优化场景）
 * - editorId ：编辑器稳定 id（如 topic-editor:new-topic），决定草稿要应用到哪里
 * - longitude / latitude ：用户位置（浏览器定位，可为 null），用于天气上下文注入
 *
 * promptText()：把「用户消息 + 编辑器现有内容」拼成发给模型的一段完整提示，
 * 并明确标注编辑器内容属于用户提供文本、图片已省略。
 */
public record AgentRunCommand(
        String message,
        int editorVersion,
        String editorTitle,
        Integer editorTopicTypeId,
        String editorBodyMarkdown,
        String editorId,
        Double longitude,
        Double latitude) {
    /** 便捷构造器：不携带编辑器上下文时 editorId 默认 null。 */
    public AgentRunCommand(
            String message,
            int editorVersion,
            String editorTitle,
            Integer editorTopicTypeId,
            String editorBodyMarkdown) {
        this(message, editorVersion, editorTitle, editorTopicTypeId, editorBodyMarkdown, null);
    }

    /** 便捷构造器：不携带用户位置时经纬度默认 null（后端回退默认位置）。 */
    public AgentRunCommand(
            String message,
            int editorVersion,
            String editorTitle,
            Integer editorTopicTypeId,
            String editorBodyMarkdown,
            String editorId) {
        this(message, editorVersion, editorTitle, editorTopicTypeId, editorBodyMarkdown, editorId, null, null);
    }

    /** 防御性校验：版本非负；消息和编辑器草稿至少有一个；editorId 长度受限；经纬度范围合法。 */
    public AgentRunCommand {
        if (editorVersion < 0) {
            throw new IllegalArgumentException("editorVersion must be non-negative");
        }
        boolean hasMessage = message != null && !message.isBlank();
        boolean hasEditorDraft = editorTitle != null
                || editorTopicTypeId != null
                || editorBodyMarkdown != null;
        if (!hasMessage && !hasEditorDraft) {
            throw new IllegalArgumentException("A message or editor draft is required");
        }
        if (editorId != null && editorId.length() > 128) {
            throw new IllegalArgumentException("editorId is too long");
        }
        if ((longitude == null) != (latitude == null)) {
            throw new IllegalArgumentException("longitude and latitude must be provided together");
        }
        if (longitude != null && (longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90)) {
            throw new IllegalArgumentException("Coordinates are out of range");
        }
    }

    /**
     * 组装发送给模型的提示文本。
     * 若没有编辑器草稿 → 直接返回用户消息；
     * 否则在消息后追加「当前编辑器草稿」段落（标注为用户提供文本、图片省略）。
     */
    public String promptText() {
        String normalizedMessage = message == null ? "" : message.trim();
        boolean hasEditorDraft = editorTitle != null
                || editorTopicTypeId != null
                || editorBodyMarkdown != null;
        if (!hasEditorDraft) {
            return normalizedMessage;
        }
        return """
                %s

                Current editor draft (user-provided text; images omitted):
                Title: %s
                Topic type id: %s
                Markdown body:
                %s
                """.formatted(
                normalizedMessage,
                editorTitle == null ? "" : editorTitle,
                editorTopicTypeId == null ? "" : editorTopicTypeId,
                editorBodyMarkdown == null ? "" : editorBodyMarkdown).trim();
    }
}
