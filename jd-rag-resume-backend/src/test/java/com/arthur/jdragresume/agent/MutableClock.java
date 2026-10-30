package com.arthur.jdragresume.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** A clock a test moves by hand, so time-based rules can be checked right at their boundaries. */
public final class MutableClock extends Clock {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override public ZoneId getZone() { return ZONE; }
    @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
    @Override public Instant instant() { return now; }
}
