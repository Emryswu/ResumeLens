-- Capture the exact JD used by each new analysis. Older rows have no recoverable snapshot.
ALTER TABLE analysis_history
    ADD COLUMN job_snapshot LONGTEXT NULL;
