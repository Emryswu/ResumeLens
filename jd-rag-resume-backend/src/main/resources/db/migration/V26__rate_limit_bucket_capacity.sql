ALTER TABLE rate_limit_buckets ADD COLUMN creation_token CHAR(36) NULL;

CREATE TABLE rate_limit_capacity (
    id TINYINT NOT NULL PRIMARY KEY,
    tracked_buckets BIGINT NOT NULL
) ENGINE=InnoDB;

INSERT INTO rate_limit_capacity(id, tracked_buckets)
SELECT 1, COUNT(*) FROM rate_limit_buckets;
