package com.example.agent.session;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 会话领域服务：会话 / 消息 / 事件 / 草稿的读写入口。
 *
 * 设计要点：\n
 * - 所有写操作都先 touchOwned（更新 updated_at 并做归属校验），\n
 * 不存在或不属于当前用户的会话立刻抛 404。\n
 * - 所有方法都带 @Transactional，保证「更新会话时间戳 + 插入子记录」原子性。\n
 * - 时间统一走注入的 Clock（测试可注入固定时钟）。\n
 * - 会话默认 30 天过期；每个用户最多保留 10 个会话（创建时清理更旧的）。
 */
@Service
public class AgentSessionService {
    private final AgentSessionMapper sessionMapper; // 会话表
    private final AgentMessageMapper messageMapper; // 消息表
    private final AgentEventMapper eventMapper; // 事件表
    private final AgentDraftMapper draftMapper; // 草稿表
    private final Clock clock; // 时钟（可注入测试时钟）

    public AgentSessionService(
            AgentSessionMapper sessionMapper,
            AgentMessageMapper messageMapper,
            AgentEventMapper eventMapper,
            AgentDraftMapper draftMapper,
            Clock clock) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.eventMapper = eventMapper;
        this.draftMapper = draftMapper;
        this.clock = clock;
    }

    /**
     * 创建新会话：状态 ACTIVE，30 天后过期；\n
     * 并清理该用户「第 10 条之后」的旧会话（控制会话数量）。
     */
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

    /** 查询当前用户最近 10 条未过期会话（前端会话下拉框）。 */
    @Transactional(readOnly = true)
    public List<AgentSession> listRecent(int uid) {
        return sessionMapper.selectRecentNonExpired(uid, Timestamp.from(clock.instant()), 10);
    }

    /**
     * 解析会话标题（列表展示用）：\n
     * 1. 优先用草稿标题；\n
     * 2. 否则取第一条非空用户消息，超 20 字截断加省略号。
     */
    @Transactional(readOnly = true)
    public String resolveTitle(AgentSession session) {
        AgentDraft draft = draftMapper.selectBySessionId(session.getId());
        if (draft != null && draft.getTitle() != null && !draft.getTitle().isBlank()) {
            return draft.getTitle();
        }
        for (AgentMessage message : messageMapper.selectBySessionIdOrdered(session.getId())) {
            if (message.getRole() == AgentMessageRole.USER
                    && message.getContent() != null
                    && !message.getContent().isBlank()) {
                String content = message.getContent().replaceAll("\\s+", " ").trim();
                return content.length() <= 20 ? content : content.substring(0, 20) + "…";
            }
        }
        return null;
    }

    /** 查询当前用户最近的 ACTIVE 会话（前端打开助手时自动恢复用）。 */
    @Transactional(readOnly = true)
    public Optional<AgentSession> findMostRecentActive(int uid) {
        return Optional.ofNullable(sessionMapper.selectMostRecentActiveNonExpired(
                uid,
                Timestamp.from(clock.instant())));
    }

    /**
     * 加载会话聚合：会话 + 消息 + 事件 + 草稿 一次取回。\n
     * 归属校验失败（不存在/非本人）抛 AgentSessionNotFoundException → HTTP 404。
     */
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
                draftMapper.selectBySessionId(sessionId));
    }

    /** 删除会话（子记录靠外键级联删除）；删不到（不存在/非本人）抛 404。 */
    @Transactional
    public void delete(int uid, long sessionId) {
        if (sessionMapper.deleteOwnedById(sessionId, uid) == 0) {
            throw new AgentSessionNotFoundException();
        }
    }

    /** 追加一条消息：先 touch 会话（校验归属 + 更新 updated_at），再插入。 */
    @Transactional
    public AgentMessage appendMessage(
            int uid,
            long sessionId,
            AgentMessageRole role,
            String content) {
        Timestamp now = touchOwned(uid, sessionId);
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(now);
        messageMapper.insert(message);
        return message;
    }

    /** 追加一条事件：先 touch 会话，再插入（runId + sequenceNo 保证唯一顺序）。 */
    @Transactional
    public AgentEvent appendEvent(
            int uid,
            long sessionId,
            String runId,
            int sequenceNo,
            String type,
            String payloadJson) {
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

    /**
     * 保存草稿：upsert（每会话一份，重复生成版本 +1）。\n
     * 保存后重新查询返回最新版本（因为 version 自增发生在数据库里）。
     */
    @Transactional
    public AgentDraft saveDraft(int uid, long sessionId, AgentDraftInput input) {
        Timestamp now = touchOwned(uid, sessionId);
        AgentDraft draft = new AgentDraft();
        draft.setSessionId(sessionId);
        draft.setVersion(1); // 首次插入为 1；重复生成由 ON DUPLICATE KEY 在库里 +1
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

    /** 删除所有过期会话（定时清理任务调用）。返回删除条数。 */
    @Transactional
    public int deleteExpiredSessions() {
        return sessionMapper.deleteExpired(Timestamp.from(clock.instant()));
    }

    /**
     * 写入会话滚动摘要并推进覆盖点（后台摘要任务调用）。
     * 带 uid 归属校验；写不进（会话已删/非本人）静默返回——摘要任务无业务后果。
     * 故意不走 touchOwned：不更新 updated_at，避免后台任务把会话顶到列表最前。
     */
    @Transactional
    public void updateContextSummary(int uid, long sessionId, String summary, long summarizedMessageId) {
        sessionMapper.updateContextSummary(sessionId, uid, summary, summarizedMessageId);
    }

    /** 查询某条消息之后（id 更大）的全部消息（后台摘要任务读取，无需 uid 校验——调用方已校验归属）。 */
    @Transactional(readOnly = true)
    public List<AgentMessage> messagesAfter(long sessionId, long afterId) {
        return messageMapper.selectBySessionIdAfterId(sessionId, afterId);
    }

    /**
     * 私有辅助：touch 会话（更新 updated_at）。\n
     * 返回 0 = 会话不存在或不属于该用户 → 抛 404；\n
     * 返回更新时间（供子记录统一使用同一时间戳）。
     */
    private Timestamp touchOwned(int uid, long sessionId) {
        Timestamp now = Timestamp.from(clock.instant());
        if (sessionMapper.touchOwnedById(sessionId, uid, now) == 0) {
            throw new AgentSessionNotFoundException();
        }
        return now;
    }
}
