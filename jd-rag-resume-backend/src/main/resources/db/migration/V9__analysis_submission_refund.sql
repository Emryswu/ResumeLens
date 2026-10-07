-- 模型输出不合规（JSON 解析失败、优势条目全部引用无效）导致的失败不是用户造成的，
-- 不应占用户的提交限流次数。analysis_submission_log 保持只追加、不改不删；
-- 退还记成另一张同样只追加的表，计数时从窗口内的提交里扣掉已退还的那些。

-- 分析记录指回它占用的那次提交，worker 判定失败时才知道该退哪一条。
-- 旧记录没有对应关系，保持 NULL（不退还）。
ALTER TABLE analysis_history
    ADD COLUMN submission_log_id BIGINT NULL,
    ADD CONSTRAINT fk_analysis_history_submission_log
        FOREIGN KEY (submission_log_id) REFERENCES analysis_submission_log (id) ON DELETE SET NULL;

CREATE TABLE analysis_submission_refund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    submission_log_id BIGINT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 一次提交最多退还一次。
    UNIQUE KEY uk_submission_refund_submission (submission_log_id),
    CONSTRAINT fk_submission_refund_log
        FOREIGN KEY (submission_log_id) REFERENCES analysis_submission_log (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
