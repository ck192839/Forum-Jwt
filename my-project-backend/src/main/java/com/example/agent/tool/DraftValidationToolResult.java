package com.example.agent.tool;

import java.util.List;

/**
 * validate_draft 工具的结果：
 * - valid ：是否通过全部约束
 * - errors ：未通过时的错误码列表（TITLE_LENGTH / BODY_LENGTH / INVALID_TOPIC_TYPE /
 * PROHIBITED_CONTENT）
 * 模型看到这个结果后决定修正草稿或放弃。
 */
public record DraftValidationToolResult(boolean valid, List<String> errors) {
    public DraftValidationToolResult {
        errors = List.copyOf(errors);
    }
}
