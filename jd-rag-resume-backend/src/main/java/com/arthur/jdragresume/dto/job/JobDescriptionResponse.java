package com.arthur.jdragresume.dto.job;

import com.arthur.jdragresume.entity.JobDescription;

import java.time.LocalDateTime;

public record JobDescriptionResponse(
        Long id,
        Long userId,
        String title,
        String companyName,
        String location,
        String employmentType,
        String description,
        String requirements,
        String sourcePlatform,
        String sourceUrl,
        String sourceJobId,
        String contentFingerprint,
        LocalDateTime lastSeenAt,
        /** FULL or LISTING; a LISTING job holds only the search-list summary, not the full JD. */
        String captureDepth,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static JobDescriptionResponse from(JobDescription jobDescription) {
        return new JobDescriptionResponse(
                jobDescription.getId(),
                jobDescription.getUser().getId(),
                jobDescription.getTitle(),
                jobDescription.getCompanyName(),
                jobDescription.getLocation(),
                jobDescription.getEmploymentType(),
                jobDescription.getDescription(),
                jobDescription.getRequirements(),
                jobDescription.getSourcePlatform(),
                jobDescription.getSourceUrl(),
                jobDescription.getSourceJobId(),
                jobDescription.getContentFingerprint(),
                jobDescription.getLastSeenAt(),
                jobDescription.getCaptureDepth().name(),
                jobDescription.getCreatedAt(),
                jobDescription.getUpdatedAt()
        );
    }
}
