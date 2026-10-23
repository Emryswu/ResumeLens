CREATE TABLE job_search_preferences (
    user_id BIGINT NOT NULL PRIMARY KEY,
    available_months INT,
    days_per_week INT,
    earliest_start_date DATE,
    preferred_district VARCHAR(80),
    district_required BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_job_preferences_user FOREIGN KEY (user_id)
        REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT chk_job_preferences_months CHECK (available_months IS NULL OR available_months BETWEEN 1 AND 24),
    CONSTRAINT chk_job_preferences_days CHECK (days_per_week IS NULL OR days_per_week BETWEEN 1 AND 7)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
