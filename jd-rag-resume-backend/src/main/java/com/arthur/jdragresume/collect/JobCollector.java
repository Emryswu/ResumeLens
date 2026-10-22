package com.arthur.jdragresume.collect;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * The collection layer: reads job listings for a search from one platform. The assistant tool
 * and the job library only see this interface, so where the listings come from (today the
 * user's browser extension; later perhaps something that runs on the server) can change
 * without touching them.
 *
 * <p>A collector either finishes in {@link #start} ({@link Collected}) or delegates the work
 * to someone else ({@link Delegated}); the delegate's report then comes back through
 * {@link #complete}, which must treat it as untrusted input.
 */
public interface JobCollector {
    /** The source platform the listings are stored under, e.g. "BOSS". */
    String platform();

    /** Validates the request against this collector's limits and starts it. */
    Start start(CollectRequest request);

    /** Turns a delegate's report for {@code request} into a result; rejects a report that does not fit. */
    CollectionResult complete(CollectRequest request, JsonNode report);

    sealed interface Start permits Collected, Delegated {
    }

    record Collected(CollectionResult result) implements Start {
    }

    /** {@code executor} names who runs {@code action} with {@code command}, e.g. the browser extension. */
    record Delegated(String executor, String action, Map<String, Object> command) implements Start {
    }
}
