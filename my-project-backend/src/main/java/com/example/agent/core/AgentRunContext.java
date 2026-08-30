package com.example.agent.core;

/**
 * 一次 Agent 运行的环境上下文（由 AgentRunService 在运行前组装）。
 *
 * - timeText ：当前时间的人读文本（如 "2026-08-30 10:15 星期六"），供模型推断饭点等
 * - weatherText ：当前位置天气摘要（当前天气 + 未来几小时预报），获取失败时为 null
 *
 * 采用「注入式」而非工具：天气查询有 Redis 缓存、成本极低，
 * 预先注入可让模型按需引用且不消耗工具调用额度。
 */
public record AgentRunContext(String timeText, String weatherText) {
}
