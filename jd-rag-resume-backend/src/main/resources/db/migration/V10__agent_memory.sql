CREATE TABLE agent_memory (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    user_id BIGINT NOT NULL,
    category VARCHAR(40) NOT NULL,
    content VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_agent_memory_user (user_id),
    CONSTRAINT fk_agent_memory_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
