ALTER TABLE agent_draft
    ADD COLUMN target_editor_id VARCHAR(128) NULL AFTER editor_version;
