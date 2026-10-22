CREATE TABLE latest_job_collection (
    user_id BIGINT NOT NULL,
    keyword VARCHAR(50) NOT NULL,
    city VARCHAR(10) NOT NULL,
    experience VARCHAR(10) NOT NULL,
    max_pages INT NOT NULL,
    pages_read INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    completed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_latest_collection_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
