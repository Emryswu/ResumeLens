package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.service.ContentFingerprints;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A copy of the JD fields used by an analysis, captured when it is submitted.
 * The job fingerprint is computed from this record, so every fingerprinted field is also snapshotted:
 * a "JD changed" notice can always be explained by the stored snapshot.
 */
public record JobSnapshot(
        String title,
        String companyName,
        String location,
        String employmentType,
        String description,
        String requirements
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JobSnapshot from(JobDescription job) {
        return new JobSnapshot(job.getTitle(), job.getCompanyName(), job.getLocation(),
                job.getEmploymentType(), job.getDescription(), job.getRequirements());
    }

    public String fingerprint() {
        return ContentFingerprints.job(title, companyName, location, employmentType, description, requirements);
    }

    public static String write(JobDescription job) {
        try {
            return MAPPER.writeValueAsString(from(job));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("job snapshot is not serializable", ex);
        }
    }

    /** Null means the report predates snapshot persistence. */
    public static JobSnapshot read(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, JobSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored job snapshot is invalid", ex);
        }
    }
}
