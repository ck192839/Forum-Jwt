package com.example.agent.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class AgentSessionService {
    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentEventMapper eventMapper;
    private final AgentDraftMapper draftMapper;
    private final Clock clock;

    public AgentSessionService(
            AgentSessionMapper sessionMapper,
            AgentMessageMapper messageMapper,
            AgentEventMapper eventMapper,
            AgentDraftMapper draftMapper,
            Clock clock
    ) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.eventMapper = eventMapper;
        this.draftMapper = draftMapper;
        this.clock = clock;
    }

    @Transactional
    public AgentSession create(int uid) {
        Instant now = clock.instant();
        AgentSession session = new AgentSession();
        session.setUid(uid);
        session.setStatus(AgentSessionStatus.ACTIVE);
        session.setCreatedAt(Timestamp.from(now));
        session.setUpdatedAt(Timestamp.from(now));
        session.setExpiresAt(Timestamp.from(now.plus(30, ChronoUnit.DAYS)));
        sessionMapper.insert(session);
        sessionMapper.deleteOlderSessions(uid, 10);
        return session;
    }

    @Transactional(readOnly = true)
    public List<AgentSession> listRecent(int uid) {
        return sessionMapper.selectRecentNonExpired(uid, Timestamp.from(clock.instant()), 10);
    }

    @Transactional(readOnly = true)
    public Optional<AgentSession> findMostRecentActive(int uid) {
        return Optional.ofNullable(sessionMapper.selectMostRecentActiveNonExpired(
                uid,
                Timestamp.from(clock.instant())
        ));
    }

    @Transactional(readOnly = true)
    public AgentSessionAggregate load(int uid, long sessionId) {
        AgentSession session = sessionMapper.selectOwnedById(sessionId, uid);
        if (session == null) {
            throw new AgentSessionNotFoundException();
        }
        return new AgentSessionAggregate(
                session,
                messageMapper.selectBySessionIdOrdered(sessionId),
                eventMapper.selectBySessionIdOrdered(sessionId),
                draftMapper.selectBySessionId(sessionId)
        );
    }

    @Transactional
    public void delete(int uid, long sessionId) {
        if (sessionMapper.deleteOwnedById(sessionId, uid) == 0) {
            throw new AgentSessionNotFoundException();
        }
    }

    @Transactional
    public AgentMessage appendMessage(
            int uid,
            long sessionId,
            AgentMessageRole role,
            String content
    ) {
        Timestamp now = touchOwned(uid, sessionId);
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        return message;
    }

    @Transactional
    public AgentEvent appendEvent(
            int uid,
            long sessionId,
            String runId,
            int sequenceNo,
            String type,
            String payloadJson
    ) {
        Timestamp now = touchOwned(uid, sessionId);
        AgentEvent event = new AgentEvent();
        event.setSessionId(sessionId);
        event.setRunId(runId);
        event.setSequenceNo(sequenceNo);
        event.setType(type);
        event.setPayloadJson(payloadJson);
        event.setCreatedAt(now);
        eventMapper.insert(event);
        return event;
    }

    @Transactional
    public AgentDraft saveDraft(int uid, long sessionId, AgentDraftInput input) {
        Timestamp now = touchOwned(uid, sessionId);
        AgentDraft draft = new AgentDraft();
        draft.setSessionId(sessionId);
        draft.setVersion(1);
        draft.setEditorVersion(input.editorVersion());
        draft.setTargetEditorId(input.targetEditorId());
        draft.setTitle(input.title());
        draft.setTopicTypeId(input.topicTypeId());
        draft.setBodyMarkdown(input.bodyMarkdown());
        draft.setCitationsJson(input.citationsJson());
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        draftMapper.upsert(draft);
        return draftMapper.selectBySessionId(sessionId);
    }

    @Transactional
    public int deleteExpiredSessions() {
        return sessionMapper.deleteExpired(Timestamp.from(clock.instant()));
    }

    private Timestamp touchOwned(int uid, long sessionId) {
        Timestamp now = Timestamp.from(clock.instant());
        if (sessionMapper.touchOwnedById(sessionId, uid, now) == 0) {
            throw new AgentSessionNotFoundException();
        }
        return now;
    }
}
