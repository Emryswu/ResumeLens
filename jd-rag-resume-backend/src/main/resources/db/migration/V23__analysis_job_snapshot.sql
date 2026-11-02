-- Capture the exact JD used by each new analysis. Older rows have no recoverable snapshot.
-- job_title_snapshot is a plain column so history search can match the title the report shows.
ALTER TABLE analysis_history
    ADD COLUMN job_snapshot LONGTEXT NULL,
    ADD COLUMN job_title_snapshot VARCHAR(160) NULL;
