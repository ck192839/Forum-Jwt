CREATE TABLE IF NOT EXISTS agent_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    uid INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_agent_session_user_updated (uid, updated_at DESC),
    INDEX idx_agent_session_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_agent_message_session (session_id, id),
    CONSTRAINT fk_agent_message_session FOREIGN KEY (session_id)
        REFERENCES agent_session (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    type VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_event_run_sequence (run_id, sequence_no),
    INDEX idx_agent_event_session (session_id, id),
    CONSTRAINT fk_agent_event_session FOREIGN KEY (session_id)
        REFERENCES agent_session (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_draft (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    version INT NOT NULL,
    editor_version INT NOT NULL,
    title VARCHAR(30) NOT NULL,
    topic_type_id INT NOT NULL,
    body_markdown MEDIUMTEXT NOT NULL,
    citations_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_draft_session (session_id),
    CONSTRAINT fk_agent_draft_session FOREIGN KEY (session_id)
        REFERENCES agent_session (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
