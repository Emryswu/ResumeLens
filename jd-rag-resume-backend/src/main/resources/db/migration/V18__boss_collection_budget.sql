CREATE TABLE boss_daily_budget (
    user_id BIGINT NOT NULL,
    budget_date DATE NOT NULL,
    pages INT NOT NULL,
    PRIMARY KEY (user_id, budget_date),
    CONSTRAINT fk_boss_daily_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE boss_page_reservation (
    reservation_id VARCHAR(240) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    budget_date DATE NOT NULL,
    reserved_pages INT NOT NULL,
    used_pages INT,
    KEY idx_boss_reservation_user_date (user_id, budget_date),
    CONSTRAINT fk_boss_reservation_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
