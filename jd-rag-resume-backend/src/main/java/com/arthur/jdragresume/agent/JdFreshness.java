package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The one rule for whether a verified BOSS JD is still current. Ranking, the verification budget and the
 * recommendation gate all ask this class instead of comparing timestamps themselves, so they cannot disagree.
 *
 * <p>Only BOSS postings age: they are the only ones {@code verify_boss_job} can read again. A FULL JD the user
 * typed or pasted has no page to re-read and stays as it is.
 */
@Component
public class JdFreshness {
    public enum Status { LISTING, FRESH, EXPIRED, NOT_APPLICABLE }

    private final Clock clock;
    private final Duration maxAge;

    @Autowired
    public JdFreshness(Clock clock, AgentProperties properties) {
        this(clock, Duration.ofDays(properties.getVerifiedJdMaxAgeDays()));
    }

    public JdFreshness(Clock clock, Duration maxAge) {
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /** A FULL row without a verification time predates the column and counts as expired. */
    public Status of(JobDescriptionResponse job) {
        if (!"FULL".equals(job.captureDepth())) return Status.LISTING;
        if (!"BOSS".equalsIgnoreCase(job.sourcePlatform())) return Status.NOT_APPLICABLE;
        LocalDateTime verifiedAt = job.fullVerifiedAt();
        if (verifiedAt == null || verifiedAt.plus(maxAge).isBefore(LocalDateTime.now(clock))) return Status.EXPIRED;
        return Status.FRESH;
    }

    public boolean expired(JobDescriptionResponse job) {
        return of(job) == Status.EXPIRED;
    }

    /** A BOSS posting whose full JD has to be (re)read before it can be recommended. */
    public boolean needsVerification(JobDescriptionResponse job) {
        Status status = of(job);
        return status == Status.EXPIRED || status == Status.LISTING && "BOSS".equalsIgnoreCase(job.sourcePlatform());
    }

    public Duration maxAge() {
        return maxAge;
    }
}
