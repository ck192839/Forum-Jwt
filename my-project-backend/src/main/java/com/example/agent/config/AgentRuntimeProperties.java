package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 运行时配置项，绑定配置文件里的 agent.execution.* 前缀。
 *
 * 默认值：
 * - maxToolCalls = 8 ：单次 run 最多工具调用次数（含运行时再校验）
 * - timeout = 60s ：单次 run 的总时间预算（模型调用 + 工具调用共享）
 * - runThreads = 4 ：run 编排线程数（并发会话数上限）
 * - callThreads = 8 ：单次模型/工具调用线程数（供超时中断用）
 * - sseTimeout = 65s ：SSE 连接超时（略大于 timeout，保证事件能推完）
 *
 * 所有 setter 都做校验：非法值（0/负数/null）直接启动失败，尽早暴露配置错误。
 */
@ConfigurationProperties("agent.execution")
public class AgentRuntimeProperties {
    private int maxToolCalls = 8; // 工具调用次数上限
    private Duration timeout = Duration.ofSeconds(60); // 单次 run 总预算
    private int runThreads = 4; // run 编排线程数
    private int callThreads = 8; // 单次调用线程数
    private Duration sseTimeout = Duration.ofSeconds(65); // SSE 超时

    public int getMaxToolCalls() {
        return maxToolCalls;
    }

    public void setMaxToolCalls(int maxToolCalls) {
        this.maxToolCalls = requirePositive(maxToolCalls, "maxToolCalls");
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
    }

    public int getRunThreads() {
        return runThreads;
    }

    public void setRunThreads(int runThreads) {
        this.runThreads = requirePositive(runThreads, "runThreads");
    }

    public int getCallThreads() {
        return callThreads;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        if (sseTimeout == null || sseTimeout.isZero() || sseTimeout.isNegative()) {
            throw new IllegalArgumentException("sseTimeout must be positive");
        }
        this.sseTimeout = sseTimeout;
    }

    public void setCallThreads(int callThreads) {
        this.callThreads = requirePositive(callThreads, "callThreads");
    }

    /** 公共校验：整型配置必须为正数。 */
    private int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
