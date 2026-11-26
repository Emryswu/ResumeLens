CREATE TABLE rate_limit_buckets (
    bucket_key VARCHAR(255) NOT NULL PRIMARY KEY,
    updated_at_ms BIGINT NOT NULL,
    KEY idx_rate_limit_bucket_updated (updated_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE rate_limit_entries (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    bucket_key VARCHAR(255) NOT NULL,
    hit_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    KEY idx_rate_limit_bucket_hit (bucket_key, hit_at_ms, id),
    KEY idx_rate_limit_expires (expires_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
