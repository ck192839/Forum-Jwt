package com.example.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Agent 模块的时钟 Bean 配置。
 *
 * 提供统一的 UTC 时钟给需要取当前时间的组件使用（如会话过期时间计算）。
 * 
 * @ConditionalOnMissingBean：如果应用里已经有 Clock Bean（比如测试里注入的固定时钟），
 *                                    就用已有的，不重复创建——方便测试控制时间。
 *                                    proxyBeanMethods = false：跳过 CGLIB
 *                                    代理，加快启动，适合纯 Bean 注册型配置类。
 */
@Configuration(proxyBeanMethods = false)
public class AgentClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock agentClock() {
        // 使用系统 UTC 时钟
        return Clock.systemUTC();
    }
}
