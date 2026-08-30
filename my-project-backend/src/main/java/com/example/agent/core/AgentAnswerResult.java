package com.example.agent.core;

import java.util.List;

/**
 * 终端结果：对用户问题的一次回答（ANSWER）。
 *
 * 与 QUESTION（追问）不同，ANSWER 是 Agent 对「论坛内容问答」的正式回复：
 * - answer ：用 Agent 自己语言组织的回答正文（Markdown 纯文本，不含链接，出处走 citations）
 * - citations ：回答依据的帖子列表（空列表表示拒答/论坛中无相关内容）
 *
 * citations 已由 AgentTerminalResultParser 按白名单严格校验：
 * topicId 必须来自工具真实返回，防止模型编造出处。
 */
public record AgentAnswerResult(String answer, List<AgentCitation> citations) implements AgentTerminalResult {
    public AgentAnswerResult {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
