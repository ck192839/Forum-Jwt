package com.example.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTerminalResultParserTest {
    private final AgentTerminalResultParser parser = new AgentTerminalResultParser(new ObjectMapper());

    @Test
    void parsesQuestionResult() {
        AgentTerminalResult result = parser.parse(
                "{\"type\":\"QUESTION\",\"question\":\"What audience should this target?\"}",
                4,
                Set.of()
        );

        AgentQuestionResult question = assertInstanceOf(AgentQuestionResult.class, result);
        assertEquals("What audience should this target?", question.question());
    }

    @Test
    void parsesDraftBoundToEditorVersionAndKnownCitations() {
        AgentTerminalResult result = parser.parse("""
                {
                  "type": "DRAFT",
                  "title": "Campus network troubleshooting",
                  "topicTypeId": 3,
                  "bodyMarkdown": "## Steps\\nRestart the client.",
                  "citations": [{"topicId": 42, "title": "Previous guide"}],
                  "basedOnEditorVersion": 7
                }
                """, 7, Set.of(42));

        AgentDraftResult draft = assertInstanceOf(AgentDraftResult.class, result);
        assertEquals("Campus network troubleshooting", draft.title());
        assertEquals(3, draft.topicTypeId());
        assertEquals(7, draft.basedOnEditorVersion());
        assertEquals(42, draft.citations().get(0).topicId());
    }

    @Test
    void rejectsUnknownResultTypeAndMarkdownCodeFences() {
        assertThrows(AgentOutputValidationException.class, () -> parser.parse(
                "```json\n{\"type\":\"ANSWER\"}\n```",
                1,
                Set.of()
        ));
    }

    @Test
    void rejectsDraftForAStaleEditorVersion() {
        assertThrows(AgentOutputValidationException.class, () -> parser.parse("""
                {"type":"DRAFT","title":"Title","topicTypeId":1,
                 "bodyMarkdown":"Body","citations":[],"basedOnEditorVersion":4}
                """, 5, Set.of()));
    }

    @Test
    void rejectsCitationThatWasNotReturnedByATool() {
        assertThrows(AgentOutputValidationException.class, () -> parser.parse("""
                {"type":"DRAFT","title":"Title","topicTypeId":1,
                 "bodyMarkdown":"Body","citations":[{"topicId":99,"title":"Invented"}],
                 "basedOnEditorVersion":5}
                """, 5, Set.of(42)));
    }

    @Test
    void rejectsUnknownFieldsAndInvalidDraftLengths() {
        assertThrows(AgentOutputValidationException.class, () -> parser.parse(
                "{\"type\":\"QUESTION\",\"question\":\"Q\",\"thought\":\"hidden\"}",
                1,
                Set.of()
        ));
        assertThrows(AgentOutputValidationException.class, () -> parser.parse("""
                {"type":"DRAFT","title":"","topicTypeId":0,
                 "bodyMarkdown":"","citations":[],"basedOnEditorVersion":1}
                """, 1, Set.of()));
    }
}
