-- Align JPA @Enumerated(STRING) @Column(length=20) with the physical schema.
ALTER TABLE analysis_history MODIFY COLUMN status VARCHAR(20) NOT NULL;
