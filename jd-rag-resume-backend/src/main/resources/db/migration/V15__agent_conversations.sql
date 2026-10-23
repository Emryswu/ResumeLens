CREATE TABLE agent_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    state VARCHAR(40) NOT NULL DEFAULT 'READY',
    current_turn VARCHAR(36),
    active_run VARCHAR(36),
    run_expires_at BIGINT,
    event_sequence BIGINT NOT NULL DEFAULT 0,
    summary LONGTEXT,
    browser_task LONGTEXT,
    browser_arguments LONGTEXT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_agent_conversation_user_updated (user_id, updated_at, id),
    KEY idx_agent_conversation_run_expiry (run_expires_at),
    CONSTRAINT fk_agent_conversation_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_conversation_message (
    conversation_id BIGINT NOT NULL,
    position BIGINT NOT NULL,
    payload LONGTEXT NOT NULL,
    PRIMARY KEY (conversation_id, position),
    CONSTRAINT fk_agent_message_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_conversation_event (
    conversation_id BIGINT NOT NULL,
    sequence BIGINT NOT NULL,
    turn_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    type VARCHAR(40) NOT NULL,
    payload LONGTEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (conversation_id, sequence),
    CONSTRAINT fk_agent_event_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
