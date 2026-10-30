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
        String collectionExperience,
        String description,
        String requirements,
        String sourcePlatform,
        String sourceUrl,
        String sourceJobId,
        String contentFingerprint,
        LocalDateTime lastSeenAt,
        /** FULL or LISTING; a LISTING job holds only the search-list summary, not the full JD. */
        String captureDepth,
        /** When the full JD was last read from the detail page; null for listings and never-verified rows. */
        LocalDateTime fullVerifiedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** Without a verification time, as before the column existed: a FULL row built this way counts as expired. */
    public JobDescriptionResponse(Long id, Long userId, String title, String companyName, String location,
                                  String employmentType, String collectionExperience, String description,
                                  String requirements, String sourcePlatform, String sourceUrl, String sourceJobId,
                                  String contentFingerprint, LocalDateTime lastSeenAt, String captureDepth,
                                  LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, userId, title, companyName, location, employmentType, collectionExperience, description,
                requirements, sourcePlatform, sourceUrl, sourceJobId, contentFingerprint, lastSeenAt, captureDepth,
                null, createdAt, updatedAt);
    }

    public static JobDescriptionResponse from(JobDescription jobDescription) {
        return new JobDescriptionResponse(
                jobDescription.getId(),
                jobDescription.getUser().getId(),
                jobDescription.getTitle(),
                jobDescription.getCompanyName(),
                jobDescription.getLocation(),
                jobDescription.getEmploymentType(),
                jobDescription.getCollectionExperience(),
                jobDescription.getDescription(),
                jobDescription.getRequirements(),
                jobDescription.getSourcePlatform(),
                jobDescription.getSourceUrl(),
                jobDescription.getSourceJobId(),
                jobDescription.getContentFingerprint(),
                jobDescription.getLastSeenAt(),
                jobDescription.getCaptureDepth().name(),
                jobDescription.getFullVerifiedAt(),
                jobDescription.getCreatedAt(),
                jobDescription.getUpdatedAt()
        );
    }
}
