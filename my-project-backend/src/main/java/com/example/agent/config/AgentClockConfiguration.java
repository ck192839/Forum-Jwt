package com.example.agent.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AgentClockConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock agentClock() {
        return Clock.systemUTC();
    }
}
