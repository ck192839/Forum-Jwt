package com.example.agent.session;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清理任务：每天凌晨 3 点删除过期会话（cron 可用 agent.session.cleanup-cron 配置覆盖）。
 * 过期会话及其消息/事件/草稿通过外键级联删除。
 */
@Component
public class AgentSessionCleanupJob {
    private final AgentSessionService sessionService;

    public AgentSessionCleanupJob(AgentSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Scheduled(cron = "${agent.session.cleanup-cron:0 0 3 * * *}")
    public void deleteExpiredSessions() {
        sessionService.deleteExpiredSessions();
    }
}
