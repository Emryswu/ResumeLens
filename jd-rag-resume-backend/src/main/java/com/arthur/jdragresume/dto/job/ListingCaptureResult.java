package com.arthur.jdragresume.dto.job;

import java.util.List;

/**
 * Outcome of saving one batch of search-list summaries. {@code notSaved} counts listings that
 * were neither saved nor already in the library because the job quota was reached.
 */
public record ListingCaptureResult(List<Saved> saved, int notSaved, boolean quotaReached, int quota) {
    public ListingCaptureResult {
        saved = List.copyOf(saved);
    }

    /** {@code existingJob} means the library already had it; its content was left untouched. */
    public record Saved(JobDescriptionResponse job, boolean existingJob) {
    }
}
