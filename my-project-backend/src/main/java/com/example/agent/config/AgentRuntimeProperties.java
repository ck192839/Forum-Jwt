package com.example.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("agent.execution")
public class AgentRuntimeProperties {
    private int maxToolCalls = 8;
    private Duration timeout = Duration.ofSeconds(60);
    private int runThreads = 4;
    private int callThreads = 8;

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

    public void setCallThreads(int callThreads) {
        this.callThreads = requirePositive(callThreads, "callThreads");
    }

    private int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
