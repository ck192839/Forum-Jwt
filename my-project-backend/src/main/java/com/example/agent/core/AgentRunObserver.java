package com.example.agent.core;

/**
 * Agent 运行的观察者接口（观察者模式）。
 *
 * 作用：让 Agent 循环在「工具开始/完成」时回调外部，
 * 由调用方（AgentRunService）把状态转成 SSE 事件推给前端（tool_started / tool_completed）。
 *
 * 两个方法都有 default 空实现 + 常量 NOOP，
 * 因此调用方可以只关心自己需要的回调，测试也能传一个什么都不做的观察者。
 */
public interface AgentRunObserver {
    /** 空观察者：不关心任何回调时直接用这个常量。 */
    AgentRunObserver NOOP = new AgentRunObserver() {
    };

    /** 工具调用开始（name 为工具名，arguments 为原始 JSON 参数串）。 */
    default void toolStarted(String name, String arguments) {
    }

    /** 工具调用完成（name 为工具名，result 为工具返回的原始 JSON）。 */
    default void toolCompleted(String name, String result) {
    }

    /**
     * 模型流式输出的「可见文本」增量（已从终态 JSON 中增量解码，
     * 不含 {type/引号等 JSON 结构，可直接展示给用户）。
     * 由调用方节流后转成 message_delta SSE 事件。
     */
    default void onModelDelta(String text) {
    }

    /**
     * 上下文治理告知：Agent 在 run 内触发了降级（如上下文溢出裁剪、工具预算耗尽），
     * 回答完整性可能受影响。由调用方转成 context_notice SSE 事件告知用户。
     */
    default void onContextNotice(String text) {
    }
}
