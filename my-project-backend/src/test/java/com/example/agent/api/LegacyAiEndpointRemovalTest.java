package com.example.agent.api;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.objenesis.SpringObjenesis;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.stereotype.Controller;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LegacyAiEndpointRemovalTest {

    @Test
    void oldAiChatUrlHasNoRequestMapping() throws Exception {
        MockMvc mvc = standaloneSetup(applicationControllers().toArray()).build();

        var result = mvc.perform(post("/api/ai/chat")
                        .contentType("application/json")
                        .content("[]"))
                .andExpect(status().isNotFound())
                .andReturn();

        assertNull(result.getHandler(), "The legacy URL must not resolve to an MVC handler");
    }

    private List<Object> applicationControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        SpringObjenesis objenesis = new SpringObjenesis();
        List<Object> controllers = scanner.findCandidateComponents("com.example").stream()
                .map(definition -> load(definition.getBeanClassName()))
                .map(type -> (Object) objenesis.newInstance(type))
                .toList();
        assertFalse(controllers.isEmpty(), "At least one application controller must be inspected");
        return controllers;
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("Unable to inspect controller " + className, exception);
        }
    }
}
