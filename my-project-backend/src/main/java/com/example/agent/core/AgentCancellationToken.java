package com.example.agent.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 线程安全的取消令牌。
 *
 * 设计目的：取消动作和 Agent 执行在不同线程（HTTP 线程调 cancel，run 线程在执行），
 * 用 AtomicBoolean 保证跨线程可见性。
 *
 * 使用方式：
 * - 外部（AgentRunService.cancel）调用 {@link #cancel()} 标记取消；
 * - Agent 循环（ForumReActAgent.ensureActive）每次迭代检查 {@link #isCancelled()}，
 * 一旦为 true 立即中断（抛 AgentRunException CANCELLED）。
 * 注意：它只是「协作式」取消——真正的中断靠 future.cancel(true) 配合。
 */
public final class AgentCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** 标记取消（幂等，可多次调用）。 */
    public void cancel() {
        cancelled.set(true);
    }

    /** 是否已被取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }
}
