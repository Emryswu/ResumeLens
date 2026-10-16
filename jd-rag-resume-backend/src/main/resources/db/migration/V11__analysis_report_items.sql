-- Structured strengths and gaps (JSON arrays of {title, detail, chunks, category, source}).
-- Reports written before this migration keep NULL and are shown from the plain-text columns.
ALTER TABLE analysis_history
    ADD COLUMN strength_items LONGTEXT NULL,
    ADD COLUMN gap_items LONGTEXT NULL;
