package com.arthur.jdragresume.security;

import com.arthur.jdragresume.security.SlidingWindowRateLimiter.Key;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlidingWindowRateLimiterTests {
    @Test
    void allowsUpToLimitThenRejectsUntilWindowExpires() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(now::get);

        assertTrue(limiter.tryAcquire(new Key("login", "1", "arthur"), 2, 1_000L));
        assertTrue(limiter.tryAcquire(new Key("login", "1", "arthur"), 2, 1_000L));
        assertFalse(limiter.tryAcquire(new Key("login", "1", "arthur"), 2, 1_000L));

        now.addAndGet(1_001L);
        assertTrue(limiter.tryAcquire(new Key("login", "1", "arthur"), 2, 1_000L));
    }

    @Test
    void isolatesKeys() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire(new Key("login", "192.0.2.1", "a"), 1, 60_000L));
        assertTrue(limiter.tryAcquire(new Key("login", "192.0.2.1", "b"), 1, 60_000L));
        assertFalse(limiter.tryAcquire(new Key("login", "192.0.2.1", "a"), 1, 60_000L));
    }

    @Test
    void expiredKeysAreRemovedWithoutBreakingActiveWindows() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(now::get);

        for (int index = 0; index < 50; index++) {
            assertTrue(limiter.tryAcquire(new Key("login", "192.0.2.1", "stale-" + index), 1, 1_000L));
        }
        assertTrue(limiter.tryAcquire(new Key("login", "192.0.2.1", "active"), 2, 5_000L));
        assertEquals(51, limiter.trackedKeyCount());

        now.addAndGet(1_001L);
        assertTrue(limiter.tryAcquire(new Key("login", "192.0.2.1", "fresh"), 1, 1_000L));
        assertEquals(2, limiter.trackedKeyCount());
        assertTrue(limiter.tryAcquire(new Key("login", "192.0.2.1", "active"), 2, 5_000L));
        assertFalse(limiter.tryAcquire(new Key("login", "192.0.2.1", "active"), 2, 5_000L));
        assertFalse(limiter.tryAcquire(new Key("login", "192.0.2.1", "fresh"), 1, 1_000L));
    }

    @Test
    void sweepIsAmortizedInsteadOfRunningOnEveryCall() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(now::get);
        long loginWindowMs = 15 * 60_000L;

        limiter.tryAcquire(new Key("login", "203.0.113.7", "seed"), 100, loginWindowMs);
        long sweepsAfterFirstCall = limiter.sweepCount();

        // 同一 IP 换用户名即可造出任意多的键；时间没有推进时，这些调用都不该再触发全量扫描
        for (int index = 0; index < 500; index++) {
            limiter.tryAcquire(new Key("login", "203.0.113.7", "user" + index), 100, loginWindowMs);
        }
        assertEquals(sweepsAfterFirstCall, limiter.sweepCount());
        assertEquals(501, limiter.trackedKeyCount());

        // 越过清理间隔后，下一次调用才承担一次全量扫描
        now.addAndGet(1_001L);
        limiter.tryAcquire(new Key("login", "203.0.113.7", "next"), 100, loginWindowMs);
        assertEquals(sweepsAfterFirstCall + 1, limiter.sweepCount());
    }

    @Test
    void atCapacityOnlyTheLargestIpLosesKeysAndOtherClientsCanLoginAndRegister() {
        AtomicLong now = new AtomicLong(1_000_000L);
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(now::get, 100);
        long loginWindowMs = 15 * 60_000L;

        for (int index = 0; index < 100; index++) {
            assertTrue(limiter.tryAcquire(new Key("login", "203.0.113.7", "user" + index), 5, loginWindowMs));
        }
        assertEquals(100, limiter.trackedKeyCount());

        // 触顶后新键被拒，跟踪表不再增长
        assertFalse(limiter.tryAcquire(new Key("login", "203.0.113.7", "overflow"), 5, loginWindowMs));
        assertEquals(100, limiter.trackedKeyCount());

        // 已在跟踪中的键不受影响，正常调用方不会被容量上限误伤
        assertTrue(limiter.tryAcquire(new Key("login", "203.0.113.7", "user0"), 5, loginWindowMs));

        assertTrue(limiter.tryAcquire(new Key("login", "203.0.113.8", null), 10, loginWindowMs));
        assertTrue(limiter.tryAcquire(new Key("login", "203.0.113.8", "user0"), 5, loginWindowMs));
        assertTrue(limiter.tryAcquire(new Key("register", "203.0.113.8", null), 5, loginWindowMs));
        assertEquals(100, limiter.trackedKeyCount());

        // 窗口过期后容量自然释放
        now.addAndGet(loginWindowMs + 1);
        assertTrue(limiter.tryAcquire(new Key("login", "203.0.113.7", "overflow"), 5, loginWindowMs));
    }
}
