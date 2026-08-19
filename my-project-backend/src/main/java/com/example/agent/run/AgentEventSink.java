package com.example.agent.run;

/**
 * Agent 事件下沉器接口（抽象 SSE 推送的目标）。
 *
 * 目前唯一实现是 {@link SseEmitterAgentEventSink}（推给浏览器）。
 * 抽象成接口的目的：
 * - AgentRunService 只依赖本接口，不直接碰 SseEmitter（解耦）；
 * - 测试可以注入一个收集事件的假 sink，断言事件序列。
 *
 * 方法语义：
 * - emit ：推送一个事件（type 决定 event: 名，eventId 用于去重/顺序）
 * - complete ：正常结束（关闭流）
 * - completeWithError ：异常结束（关闭流并标记错误）
 */
public interface AgentEventSink {
    void emit(AgentSseEventType type, String eventId, Object payload);

    void complete();

    void completeWithError(Throwable error);
}
