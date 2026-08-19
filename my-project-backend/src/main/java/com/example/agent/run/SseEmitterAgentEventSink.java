package com.example.agent.run;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link AgentEventSink} 的 SSE 实现：把事件写到 SseEmitter 推给浏览器。
 *
 * 关键设计：
 * - terminated 原子标志：保证「完成/断开/出错」只执行一次（compareAndSet），
 * 防止事件推送与断连回调并发导致重复 complete。
 * - 三种终止方式（onTimeout / onError / onCompletion）统一走 disconnect()，
 * 只触发一次外部取消回调（AgentController 里传的 cancel Runnable，用于取消 run）。
 * - 发送失败（客户端断开/响应已完成）时标记终止并执行断连清理。
 */
public final class SseEmitterAgentEventSink implements AgentEventSink {
    private final SseEmitter emitter; // 底层 SSE 发射器
    private final Runnable disconnectHandler; // 断连回调（取消正在执行的 run）
    private final AtomicBoolean terminated = new AtomicBoolean(); // 是否已终止

    public SseEmitterAgentEventSink(SseEmitter emitter, Runnable disconnectHandler) {
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.disconnectHandler = Objects.requireNonNull(disconnectHandler, "disconnectHandler");
        // 注册三种终止回调，统一处理为断连
        emitter.onTimeout(this::disconnect);
        emitter.onError(error -> disconnect());
        emitter.onCompletion(this::disconnect);
    }

    /**
     * 推送一个事件。已终止则直接丢弃（避免往已关闭的流里写）。
     */
    @Override
    public void emit(AgentSseEventType type, String eventId, Object payload) {
        if (terminated.get()) {
            return;
        }
        // 构造 SSE 事件：event: <wireName>，id: <eventId>，data: <payload JSON>
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .id(eventId)
                .name(type.wireName())
                .data(payload);
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            // 客户端已断开或容器已关闭响应 → 终止并清理
            terminateDisconnected(exception);
        }
    }

    /** 正常结束：只执行一次。 */
    @Override
    public void complete() {
        if (terminated.compareAndSet(false, true)) {
            completeEmitter(emitter::complete);
        }
    }

    /** 异常结束：只执行一次。 */
    @Override
    public void completeWithError(Throwable error) {
        if (terminated.compareAndSet(false, true)) {
            completeEmitter(() -> emitter.completeWithError(error));
        }
    }

    /** 断连回调（只执行一次）：触发外部取消。 */
    private void disconnect() {
        if (terminated.compareAndSet(false, true)) {
            disconnectHandler.run();
        }
    }

    /** 发送失败时的终止：触发断连清理，再以错误结束流。 */
    private void terminateDisconnected(Throwable error) {
        if (terminated.compareAndSet(false, true)) {
            try {
                disconnectHandler.run();
            } finally {
                completeEmitter(() -> emitter.completeWithError(error));
            }
        }
    }

    /** 安全地完成 emitter：容器已关闭响应时忽略异常。 */
    private void completeEmitter(Runnable completion) {
        try {
            completion.run();
        } catch (IllegalStateException ignored) {
            // The servlet response was already completed by the container.
        }
    }
}
