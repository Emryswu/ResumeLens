package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.JobDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A copy of the JD fields shown in the report, captured when the analysis is submitted. */
public record JobSnapshot(
        String title,
        String companyName,
        String location,
        String description,
        String requirements
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JobSnapshot from(JobDescription job) {
        return new JobSnapshot(job.getTitle(), job.getCompanyName(), job.getLocation(),
                job.getDescription(), job.getRequirements());
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
