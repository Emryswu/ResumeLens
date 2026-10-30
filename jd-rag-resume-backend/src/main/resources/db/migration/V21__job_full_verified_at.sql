-- When the full JD was last read from the posting's detail page; NULL means never (or before this column existed).
ALTER TABLE job_description
    ADD COLUMN full_verified_at DATETIME(6) NULL;
