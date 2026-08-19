package com.example.agent.run;

/**
 * message_delta 事件负载：模型流式输出的文本增量。
 * 当前实现预留（DeepSeek 流式在 Agent 内部聚合后才返回终态），
 * 前端状态机（agentState.js）仍保留对该事件的累加逻辑。
 */
public record MessageDeltaPayload(String text) {
}
