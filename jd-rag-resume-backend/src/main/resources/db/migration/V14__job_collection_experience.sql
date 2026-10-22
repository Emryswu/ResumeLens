-- Existing records stay unknown until collected again; employment_type cannot prove the collection filter.
ALTER TABLE job_description ADD COLUMN collection_experience VARCHAR(10) NULL;
