package com.arthur.jdragresume.collect;

/**
 * One job as a collector read it from a search list: a summary, never the full posting.
 * {@code captureSource} says where the fields came from ("api" or "dom" for the browser extension).
 */
public record CollectedListing(
        String title,
        String companyName,
        String location,
        String employmentType,
        String description,
        String requirements,
        String sourceUrl,
        String sourceJobId,
        String captureSource
) {
}
