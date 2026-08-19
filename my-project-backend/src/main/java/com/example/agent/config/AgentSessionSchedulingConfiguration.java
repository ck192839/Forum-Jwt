package com.example.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 Spring 定时任务调度，供 {@link com.example.agent.session.AgentSessionCleanupJob}
 * 使用。
 *
 * 注意：@EnableScheduling 是全局开关（作用范围是整个应用），
 * 放在 Agent 配置类里只是「声明位置」，实际影响全局的 @Scheduled 注解。
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AgentSessionSchedulingConfiguration {
}
