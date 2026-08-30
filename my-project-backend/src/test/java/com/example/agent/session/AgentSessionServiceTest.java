package com.example.agent.session;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentSessionServiceTest {

    @Test
    void createsThirtyDaySessionAndTrimsOlderSessions() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        AgentDraftMapper draftMapper = mock(AgentDraftMapper.class);
        doAnswer(invocation -> {
            AgentSession session = invocation.getArgument(0);
            session.setId(99L);
            return 1;
        }).when(sessionMapper).insert(any(AgentSession.class));
        Clock clock = Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC);
        AgentSessionService service = new AgentSessionService(
                sessionMapper, messageMapper, eventMapper, draftMapper, clock
        );

        AgentSession result = service.create(7);

        assertEquals(99L, result.getId());
        assertEquals(7, result.getUid());
        assertEquals(AgentSessionStatus.ACTIVE, result.getStatus());
        assertEquals(Instant.parse("2026-09-03T12:00:00Z"), result.getExpiresAt().toInstant());
        verify(sessionMapper).deleteOlderSessions(7, 10);
    }

    @Test
    void writesContextSummaryWithoutTouchingSessionRecency() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC));

        service.updateContextSummary(7, 99L, "compressed summary", 42L);

        verify(sessionMapper).updateContextSummary(99L, 7, "compressed summary", 42L);
        // 后台摘要不 touch 会话：updated_at 不应被推进（否则会话会被顶到列表最前）
        verify(sessionMapper, never()).touchOwnedById(anyLong(), anyInt(), any(Timestamp.class));
    }

    @Test
    void readsMessagesAfterTheSummaryCoveragePoint() {
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentMessage recent = new AgentMessage();
        recent.setId(41L);
        recent.setSessionId(99L);
        recent.setRole(AgentMessageRole.USER);
        recent.setContent("recent");
        when(messageMapper.selectBySessionIdAfterId(99L, 40L)).thenReturn(List.of(recent));
        AgentSessionService service = new AgentSessionService(
                mock(AgentSessionMapper.class),
                messageMapper,
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC));

        List<AgentMessage> result = service.messagesAfter(99L, 40L);

        assertEquals(1, result.size());
        assertEquals("recent", result.get(0).getContent());
    }

    @Test
    void listsAtMostTenNonExpiredSessionsNewestFirst() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSession newest = new AgentSession();
        newest.setId(20L);
        AgentSession older = new AgentSession();
        older.setId(10L);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-04T12:00:00Z"));
        when(sessionMapper.selectRecentNonExpired(7, now, 10)).thenReturn(List.of(newest, older));
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );

        List<AgentSession> result = service.listRecent(7);

        assertEquals(List.of(newest, older), result);
        verify(sessionMapper).selectRecentNonExpired(7, now, 10);
    }

    @Test
    void findsMostRecentActiveNonExpiredSessionForDefaultRestore() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentSession active = new AgentSession();
        active.setId(20L);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-04T12:00:00Z"));
        when(sessionMapper.selectMostRecentActiveNonExpired(7, now)).thenReturn(active);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );

        Optional<AgentSession> result = service.findMostRecentActive(7);

        assertEquals(Optional.of(active), result);
        verify(sessionMapper).selectMostRecentActiveNonExpired(7, now);
    }

    @Test
    void loadsOwnedSessionAggregateWithOrderedMessagesEventsAndDraft() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        AgentDraftMapper draftMapper = mock(AgentDraftMapper.class);
        AgentSession session = new AgentSession();
        session.setId(99L);
        AgentMessage firstMessage = new AgentMessage();
        firstMessage.setId(1L);
        AgentMessage secondMessage = new AgentMessage();
        secondMessage.setId(2L);
        AgentEvent firstEvent = new AgentEvent();
        firstEvent.setId(3L);
        AgentEvent secondEvent = new AgentEvent();
        secondEvent.setId(4L);
        AgentDraft draft = new AgentDraft();
        draft.setVersion(2);
        when(sessionMapper.selectOwnedById(99L, 7)).thenReturn(session);
        when(messageMapper.selectBySessionIdOrdered(99L)).thenReturn(List.of(firstMessage, secondMessage));
        when(eventMapper.selectBySessionIdOrdered(99L)).thenReturn(List.of(firstEvent, secondEvent));
        when(draftMapper.selectBySessionId(99L)).thenReturn(draft);
        AgentSessionService service = new AgentSessionService(
                sessionMapper, messageMapper, eventMapper, draftMapper, Clock.systemUTC()
        );

        AgentSessionAggregate result = service.load(7, 99L);

        assertEquals(session, result.session());
        assertEquals(List.of(firstMessage, secondMessage), result.messages());
        assertEquals(List.of(firstEvent, secondEvent), result.events());
        assertEquals(draft, result.draft());
    }

    @Test
    void refusesToLoadMissingOrForeignSessionWithoutReadingChildData() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        AgentDraftMapper draftMapper = mock(AgentDraftMapper.class);
        when(sessionMapper.selectOwnedById(99L, 7)).thenReturn(null);
        AgentSessionService service = new AgentSessionService(
                sessionMapper, messageMapper, eventMapper, draftMapper, Clock.systemUTC()
        );

        assertThrows(AgentSessionNotFoundException.class, () -> service.load(7, 99L));

        verifyNoInteractions(messageMapper, eventMapper, draftMapper);
    }

    @Test
    void deletesOwnedSession() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        when(sessionMapper.deleteOwnedById(99L, 7)).thenReturn(1);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.systemUTC()
        );

        service.delete(7, 99L);

        verify(sessionMapper).deleteOwnedById(99L, 7);
    }

    @Test
    void refusesToDeleteMissingOrForeignSession() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        when(sessionMapper.deleteOwnedById(99L, 7)).thenReturn(0);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.systemUTC()
        );

        assertThrows(AgentSessionNotFoundException.class, () -> service.delete(7, 99L));
    }

    @Test
    void appendsMessageToOwnedSessionAndAdvancesRecency() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-04T12:00:00Z"));
        when(sessionMapper.touchOwnedById(99L, 7, now)).thenReturn(1);
        doAnswer(invocation -> {
            AgentMessage message = invocation.getArgument(0);
            message.setId(5L);
            return 1;
        }).when(messageMapper).insert(any(AgentMessage.class));
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                messageMapper,
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );

        AgentMessage result = service.appendMessage(7, 99L, AgentMessageRole.USER, "hello");

        assertEquals(5L, result.getId());
        assertEquals(99L, result.getSessionId());
        assertEquals(AgentMessageRole.USER, result.getRole());
        assertEquals("hello", result.getContent());
        assertEquals(now, result.getCreatedAt());
        verify(sessionMapper).touchOwnedById(99L, 7, now);
    }

    @Test
    void appendsEventToOwnedSessionAndAdvancesRecency() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentEventMapper eventMapper = mock(AgentEventMapper.class);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-04T12:00:00Z"));
        when(sessionMapper.touchOwnedById(99L, 7, now)).thenReturn(1);
        doAnswer(invocation -> {
            AgentEvent event = invocation.getArgument(0);
            event.setId(6L);
            return 1;
        }).when(eventMapper).insert(any(AgentEvent.class));
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                eventMapper,
                mock(AgentDraftMapper.class),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );

        AgentEvent result = service.appendEvent(7, 99L, "run-1", 3, "tool", "{\"ok\":true}");

        assertEquals(6L, result.getId());
        assertEquals(99L, result.getSessionId());
        assertEquals("run-1", result.getRunId());
        assertEquals(3, result.getSequenceNo());
        assertEquals("tool", result.getType());
        assertEquals("{\"ok\":true}", result.getPayloadJson());
        assertEquals(now, result.getCreatedAt());
        verify(sessionMapper).touchOwnedById(99L, 7, now);
    }

    @Test
    void refusesToAppendToMissingOrForeignSession() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        when(sessionMapper.touchOwnedById(anyLong(), anyInt(), any(Timestamp.class)))
                .thenReturn(0);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                messageMapper,
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.systemUTC()
        );

        assertThrows(
                AgentSessionNotFoundException.class,
                () -> service.appendMessage(7, 99L, AgentMessageRole.USER, "hello")
        );

        verify(messageMapper, never()).insert(any(AgentMessage.class));
    }

    @Test
    void upsertsDraftWithSuppliedEditorVersionAndReturnsPersistedVersion() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        AgentDraftMapper draftMapper = mock(AgentDraftMapper.class);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-04T12:00:00Z"));
        when(sessionMapper.touchOwnedById(99L, 7, now)).thenReturn(1);
        AgentDraft persisted = new AgentDraft();
        persisted.setSessionId(99L);
        persisted.setVersion(4);
        persisted.setEditorVersion(12);
        when(draftMapper.selectBySessionId(99L)).thenReturn(persisted);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                draftMapper,
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );
        AgentDraftInput input = new AgentDraftInput(12, "Title", 3, "Body", "[]");

        AgentDraft result = service.saveDraft(7, 99L, input);

        ArgumentCaptor<AgentDraft> captor = ArgumentCaptor.forClass(AgentDraft.class);
        verify(draftMapper).upsert(captor.capture());
        AgentDraft upserted = captor.getValue();
        assertEquals(99L, upserted.getSessionId());
        assertEquals(1, upserted.getVersion());
        assertEquals(12, upserted.getEditorVersion());
        assertEquals("Title", upserted.getTitle());
        assertEquals(3, upserted.getTopicTypeId());
        assertEquals("Body", upserted.getBodyMarkdown());
        assertEquals("[]", upserted.getCitationsJson());
        assertEquals(now, upserted.getCreatedAt());
        assertEquals(now, upserted.getUpdatedAt());
        assertEquals(4, result.getVersion());
        assertEquals(12, result.getEditorVersion());
    }

    @Test
    void deletesExpiredSessionsAtCurrentClockTime() {
        AgentSessionMapper sessionMapper = mock(AgentSessionMapper.class);
        Timestamp now = Timestamp.from(Instant.parse("2026-08-04T12:00:00Z"));
        when(sessionMapper.deleteExpired(now)).thenReturn(3);
        AgentSessionService service = new AgentSessionService(
                sessionMapper,
                mock(AgentMessageMapper.class),
                mock(AgentEventMapper.class),
                mock(AgentDraftMapper.class),
                Clock.fixed(now.toInstant(), ZoneOffset.UTC)
        );

        int deleted = service.deleteExpiredSessions();

        assertEquals(3, deleted);
        verify(sessionMapper).deleteExpired(now);
    }
}
