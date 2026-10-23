CREATE TABLE job_application (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    job_description_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    note VARCHAR(300) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_job_application_user_job UNIQUE (user_id, job_description_id),
    KEY idx_job_application_user_updated (user_id, updated_at),
    CONSTRAINT fk_job_application_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_job_application_job FOREIGN KEY (job_description_id) REFERENCES job_description (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
