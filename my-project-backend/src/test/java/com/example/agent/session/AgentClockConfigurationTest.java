package com.example.agent.session;

import com.example.agent.config.AgentClockConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AgentClockConfigurationTest {

    @Test
    void providesUtcClockByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                AgentClockConfiguration.class
        )) {
            assertEquals(ZoneOffset.UTC, context.getBean(Clock.class).getZone());
        }
    }

    @Test
    void backsOffWhenTestProvidesFixedClock() {
        Clock fixed = Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Clock.class, () -> fixed);
            context.register(AgentClockConfiguration.class);
            context.refresh();

            assertSame(fixed, context.getBean(Clock.class));
        }
    }
}
