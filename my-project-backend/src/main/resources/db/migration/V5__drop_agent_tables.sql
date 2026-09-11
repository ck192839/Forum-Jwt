-- 智能体（agent）模块退役：删除其会话、消息、事件、草稿表。
-- 模块代码已于同分支移除，完整实现保留在 master 分支历史中。

DROP TABLE IF EXISTS agent_event;
DROP TABLE IF EXISTS agent_message;
DROP TABLE IF EXISTS agent_draft;
DROP TABLE IF EXISTS agent_session;
