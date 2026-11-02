package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.service.ContentFingerprints;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record AnalysisHistoryResponse(
        Long id,
        Long userId,
        String username,
        Long resumeId,
        String resumeTitle,
        Long jobDescriptionId,
        String jobTitle,
        BigDecimal matchScore,
        AnalysisStatus status,
        String summary,
        String retrievedContext,
        String strengths,
        String missingSkills,
        /* Null for reports written before structured findings; the client then reads the text fields. */
        List<ReportItem> strengthItems,
        List<ReportItem> gapItems,
        String improvementSuggestions,
        String interviewQuestions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        JobSnapshot jobSnapshot,
        boolean jobInputChanged
) {
    /** Retain the pre-snapshot constructor for callers building simple report fixtures. */
    public AnalysisHistoryResponse(Long id, Long userId, String username, Long resumeId, String resumeTitle,
                                   Long jobDescriptionId, String jobTitle, BigDecimal matchScore,
                                   AnalysisStatus status, String summary, String retrievedContext, String strengths,
                                   String missingSkills, List<ReportItem> strengthItems, List<ReportItem> gapItems,
                                   String improvementSuggestions, String interviewQuestions, LocalDateTime createdAt,
                                   LocalDateTime updatedAt) {
        this(id, userId, username, resumeId, resumeTitle, jobDescriptionId, jobTitle, matchScore, status,
                summary, retrievedContext, strengths, missingSkills, strengthItems, gapItems,
                improvementSuggestions, interviewQuestions, createdAt, updatedAt, null, false);
    }

    /**
     * Row for the paged history list: the same report without the JD snapshot, which can be as long as the JD.
     * The list is not rendered as a report; opening a row fetches {@link #from} by id.
     */
    public static AnalysisHistoryResponse listItem(AnalysisHistory history) {
        AnalysisHistoryResponse full = from(history);
        return new AnalysisHistoryResponse(full.id(), full.userId(), full.username(), full.resumeId(), full.resumeTitle(),
                full.jobDescriptionId(), full.jobTitle(), full.matchScore(), full.status(), full.summary(),
                full.retrievedContext(), full.strengths(), full.missingSkills(), full.strengthItems(), full.gapItems(),
                full.improvementSuggestions(), full.interviewQuestions(), full.createdAt(), full.updatedAt(),
                null, full.jobInputChanged());
    }

    public static AnalysisHistoryResponse from(AnalysisHistory history) {
        JobSnapshot snapshot = JobSnapshot.read(history.getJobSnapshot());
        boolean jobChanged = history.getJobFingerprint() == null
                || !history.getJobFingerprint().equals(ContentFingerprints.job(history.getJobDescription()));
        // Pre-V23 reports have no stored JD. If the fingerprint still matches, capture the equivalent
        // live content here in the same read rather than fetching it later in the browser.
        if (snapshot == null && !jobChanged) {
            snapshot = JobSnapshot.from(history.getJobDescription());
        }
        return new AnalysisHistoryResponse(
                history.getId(),
                history.getUser().getId(),
                history.getUser().getUsername(),
                history.getResume().getId(),
                history.getResume().getTitle(),
                history.getJobDescription().getId(),
                history.jobTitleForDisplay(),
                history.getMatchScore(),
                history.getStatus(),
                history.getSummary(),
                history.getRetrievedContext(),
                history.getStrengths(),
                history.getMissingSkills(),
                ReportItems.read(history.getStrengthItems()),
                ReportItems.read(history.getGapItems()),
                history.getImprovementSuggestions(),
                history.getInterviewQuestions(),
                history.getCreatedAt(),
                history.getUpdatedAt(),
                snapshot,
                jobChanged
        );
    }
}
