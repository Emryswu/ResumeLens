ALTER TABLE job_search_preferences
    ADD COLUMN education_level INT NULL,
    ADD COLUMN expected_graduation_date DATE NULL,
    ADD COLUMN work_experience_years INT NULL,
    ADD COLUMN currently_student BOOLEAN NULL,
    ADD CONSTRAINT chk_job_preferences_education CHECK (education_level IS NULL OR education_level BETWEEN 1 AND 4),
    ADD CONSTRAINT chk_job_preferences_experience CHECK (work_experience_years IS NULL OR work_experience_years BETWEEN 0 AND 50);
