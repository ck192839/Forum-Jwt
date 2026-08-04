package com.example.agent.session;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
