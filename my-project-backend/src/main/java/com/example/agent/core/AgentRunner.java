package com.example.agent.core;

/**
 * Agent 运行器接口：模块的核心抽象（可替换点）。
 *
 * 目前唯一实现是 {@link ForumReActAgent}（ReAct 循环）。
 * 之所以抽象成接口：
 * - 生产代码（AgentRunService）只依赖本接口，不依赖具体实现；
 * - 测试可以注入假实现来隔离验证编排逻辑；
 * - 将来想换 Agent 算法（如 Plan-and-Execute）时不需要改动上层。
 *
 * 三个入参：
 * - request ：本次运行的用户输入 + 编辑器版本 + 历史消息
 * - cancellation：取消令牌（线程安全，可在任意点检查）
 * - observer ：运行观察者（回调工具开始/完成，用于推送 SSE 状态）
 */
@FunctionalInterface
public interface AgentRunner {
    AgentTerminalResult run(
            AgentRunRequest request,
            AgentCancellationToken cancellation,
            AgentRunObserver observer);
}
