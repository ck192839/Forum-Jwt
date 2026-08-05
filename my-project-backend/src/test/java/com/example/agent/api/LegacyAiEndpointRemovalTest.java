package com.example.agent.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyAiEndpointRemovalTest {

    @Test
    void oldAiChatControllerAndServiceAreRemoved() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.example.controller.AiChatController"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.example.service.AiService"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.example.service.impl.AiServiceImpl"));
    }
}
