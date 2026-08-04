package com.example.agent.session;

import com.example.agent.config.AgentSessionSchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentSessionCleanupJobTest {

    @Test
    void invokesExpiredSessionCleanup() {
        AgentSessionService sessionService = mock(AgentSessionService.class);
        AgentSessionCleanupJob job = new AgentSessionCleanupJob(sessionService);

        job.deleteExpiredSessions();

        verify(sessionService).deleteExpiredSessions();
    }

    @Test
    void enablesSpringScheduling() {
        assertNotNull(AnnotatedElementUtils.findMergedAnnotation(
                AgentSessionSchedulingConfiguration.class,
                EnableScheduling.class
        ));
    }
}
