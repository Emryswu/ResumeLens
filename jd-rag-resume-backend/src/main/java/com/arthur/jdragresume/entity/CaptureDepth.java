package com.arthur.jdragresume.entity;

/** How much of a job posting was read when it entered the library. */
public enum CaptureDepth {
    /** The complete posting: typed in, imported, or captured on its detail page. */
    FULL,
    /** Only the search-list summary (title, company, salary, tags); the full JD was never read. */
    LISTING
}
