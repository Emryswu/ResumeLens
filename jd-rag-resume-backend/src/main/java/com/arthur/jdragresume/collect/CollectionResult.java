package com.arthur.jdragresume.collect;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a collection produced. {@code code} is the finish reason when it COMPLETED (MAX_PAGES,
 * NO_RESULTS, ...), the stop reason when it STOPPED early (CAPTCHA, BOSS_NOT_LOGGED_IN, ...) and
 * the rejection when it FAILED before reading anything (EXTENSION_NOT_FOUND, BUSY, ...).
 * {@code message} is always server-side text, never text the browser sent.
 */
public record CollectionResult(
        Outcome outcome,
        String code,
        String message,
        int pagesRead,
        List<CollectedListing> listings,
        int skippedWithoutId,
        Integer dailyPagesLeft,
        List<PageRead> pageLog,
        String switchReason
) {
    public CollectionResult {
        listings = List.copyOf(listings);
        pageLog = List.copyOf(pageLog);
    }

    public CollectionResult(Outcome outcome, String code, String message, int pagesRead,
                            List<CollectedListing> listings, int skippedWithoutId, Integer dailyPagesLeft) {
        this(outcome, code, message, pagesRead, listings, skippedWithoutId, dailyPagesLeft, List.of(), null);
    }

    public CollectionResult(Outcome outcome, String code, String message, int pagesRead, List<CollectedListing> listings,
                            int skippedWithoutId, Integer dailyPagesLeft, List<PageRead> pageLog) {
        this(outcome, code, message, pagesRead, listings, skippedWithoutId, dailyPagesLeft, pageLog, null);
    }

    public CollectionResult withDiagnostics(int left, String reason) {
        return new CollectionResult(outcome, code, message, pagesRead, listings, skippedWithoutId, left, pageLog, reason);
    }

    public record PageRead(int page, int api, int dom) { }

    public static CollectionResult failed(String code, String message) {
        return new CollectionResult(Outcome.FAILED, code, message, 0, List.of(), 0, null);
    }

    /** How many listings came from each source, e.g. {api=30, dom=2}. */
    public Map<String, Integer> sources() {
        Map<String, Integer> counts = new TreeMap<>();
        listings.forEach(listing -> counts.merge(listing.captureSource(), 1, Integer::sum));
        return counts;
    }

    public enum Outcome {
        COMPLETED, STOPPED, FAILED
    }
}
