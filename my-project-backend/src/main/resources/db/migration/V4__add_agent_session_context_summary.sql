-- 会话上下文治理：滚动摘要持久化字段
-- context_summary      ：已被摘要覆盖的历史消息压缩文本（NULL 表示尚无摘要）
-- summarized_message_id：摘要覆盖到的最后一条 agent_message.id（0 表示尚未覆盖任何消息）。
--                        run 组装 prompt 时只逐字携带 id 大于该值的消息，更早的由摘要代表。
ALTER TABLE agent_session
    ADD COLUMN context_summary MEDIUMTEXT NULL,
    ADD COLUMN summarized_message_id BIGINT NOT NULL DEFAULT 0;
