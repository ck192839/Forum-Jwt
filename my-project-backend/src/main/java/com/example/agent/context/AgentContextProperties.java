package com.example.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 上下文治理配置项，绑定配置文件里的 agent.context.* 前缀。
 *
 * 背景：会话历史与工具往返会持续膨胀 prompt，超过模型上下文窗口后 run 必然失败。
 * 治理手段（各配置项对应一层防线）：
 * - maxContextTokens / recentWindowMessages ：AgentContextPlanner 组装 prompt 历史时的预算
 * - summaryMaxChars / summarizeMarginMessages / summarizeTimeout ：AgentSessionSummarizer 滚动摘要
 * - excerptMaxChars / readTopicMaxChars ：ForumAuthoringTools 工具出口截断
 *
 * 默认值按 DeepSeek 64k 上下文对账：38k 历史 + 8k(系统提示+截断后用户请求) + 10k 工具预算，留 ~8k 输出头余量。
 * 所有 setter 都做校验：非法值（0/负数/null）直接启动失败，尽早暴露配置错误。
 */
@ConfigurationProperties("agent.context")
public class AgentContextProperties {
    private int maxContextTokens = 38_000; // prompt 历史+摘要部分的 token 预算
    private int recentWindowMessages = 20; // 逐字保留的最近消息条数
    private int summaryMaxChars = 4_000; // 滚动摘要的最大字符数
    private int summarizeMarginMessages = 4; // 可摘要旧消息数超过该余量才触发摘要
    private Duration summarizeTimeout = Duration.ofSeconds(30); // 单次摘要 LLM 调用超时
    private int excerptMaxChars = 200; // 检索工具返回摘要的截断长度
    private int readTopicMaxChars = 8_000; // 读帖工具返回正文的截断长度
    private int maxToolTokens = 10_000; // 单次 run 内工具结果+参数累计 token 预算
    private int summarizeThreads = 2; // 摘要任务线程数（按会话互斥，不同会话并行）
    private int maxUserMessageChars = 6_000; // 当前用户请求的字符上限（头尾保留截断）

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    public void setMaxContextTokens(int maxContextTokens) {
        this.maxContextTokens = requirePositive(maxContextTokens, "maxContextTokens");
    }

    public int getRecentWindowMessages() {
        return recentWindowMessages;
    }

    public void setRecentWindowMessages(int recentWindowMessages) {
        this.recentWindowMessages = requirePositive(recentWindowMessages, "recentWindowMessages");
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = requirePositive(summaryMaxChars, "summaryMaxChars");
    }

    public int getSummarizeMarginMessages() {
        return summarizeMarginMessages;
    }

    public void setSummarizeMarginMessages(int summarizeMarginMessages) {
        this.summarizeMarginMessages = requirePositive(summarizeMarginMessages, "summarizeMarginMessages");
    }

    public Duration getSummarizeTimeout() {
        return summarizeTimeout;
    }

    public void setSummarizeTimeout(Duration summarizeTimeout) {
        if (summarizeTimeout == null || summarizeTimeout.isZero() || summarizeTimeout.isNegative()) {
            throw new IllegalArgumentException("summarizeTimeout must be positive");
        }
        this.summarizeTimeout = summarizeTimeout;
    }

    public int getExcerptMaxChars() {
        return excerptMaxChars;
    }

    public void setExcerptMaxChars(int excerptMaxChars) {
        this.excerptMaxChars = requirePositive(excerptMaxChars, "excerptMaxChars");
    }

    public int getReadTopicMaxChars() {
        return readTopicMaxChars;
    }

    public void setReadTopicMaxChars(int readTopicMaxChars) {
        this.readTopicMaxChars = requirePositive(readTopicMaxChars, "readTopicMaxChars");
    }

    public int getMaxToolTokens() {
        return maxToolTokens;
    }

    public void setMaxToolTokens(int maxToolTokens) {
        this.maxToolTokens = requirePositive(maxToolTokens, "maxToolTokens");
    }

    public int getSummarizeThreads() {
        return summarizeThreads;
    }

    public void setSummarizeThreads(int summarizeThreads) {
        this.summarizeThreads = requirePositive(summarizeThreads, "summarizeThreads");
    }

    public int getMaxUserMessageChars() {
        return maxUserMessageChars;
    }

    public void setMaxUserMessageChars(int maxUserMessageChars) {
        this.maxUserMessageChars = requirePositive(maxUserMessageChars, "maxUserMessageChars");
    }

    /** 公共校验：整型配置必须为正数。 */
    private int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
