package com.arthur.jdragresume.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;

@Component
public class SlidingWindowRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    /**
     * 全量清理的最小间隔。清理只为回收内存，晚一秒回收没有可观察的影响，
     * 而限流窗口本身是分钟级的 —— 同一秒内反复全量扫描纯属浪费。
     */
    private static final long SWEEP_INTERVAL_MS = 1_000L;

    /**
     * 跟踪键的数量上限。登录限流键包含调用方可控的 username，
     * 单个 IP 换用户名即可在一个窗口内造出任意多的键，必须有硬上限兜底。
     */
    private static final int DEFAULT_MAX_TRACKED_KEYS = 100_000;

    private static final long CAPACITY_WARN_INTERVAL_MS = 60_000L;

    // Existing authenticated agent quotas stay per user across changes of IP.
    public record Key(String scope, String ip, String subject) {
        public static Key agent(long userId) {
            return new Key("agent", null, Long.toString(userId));
        }

        String owner() {
            return ip != null ? ip : subject;
        }
    }

    private final Map<String, Account> accounts = new HashMap<>();
    private final TreeSet<Account> bySize = new TreeSet<>(Comparator
            .comparingInt((Account a) -> a.windows.size()).thenComparing(a -> a.owner));
    private final LongSupplier clock;
    private final int maxTrackedKeys;
    private int trackedKeys;
    private long lastSweepAt;
    private long lastCapacityWarnAt;
    private long sweeps;

    public SlidingWindowRateLimiter() {
        this(System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(LongSupplier clock) {
        this(clock, DEFAULT_MAX_TRACKED_KEYS);
    }

    public SlidingWindowRateLimiter(LongSupplier clock, int maxTrackedKeys) {
        this.clock = clock;
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
    }

    // Window admission, eviction and accounting must be one atomic operation.
    public synchronized boolean tryAcquire(Key key, int limit, long windowMs) {
        if (limit <= 0) return false;
        long now = clock.getAsLong();
        if (now - lastSweepAt >= SWEEP_INTERVAL_MS) sweep(now);
        Account account = accounts.get(key.owner());
        KeyWindow window = account == null ? null : account.windows.get(key);
        if (window == null) {
            if (trackedKeys == maxTrackedKeys) {
                Account largest = bySize.last();
                if (account != null && account.windows.size() == largest.windows.size()) {
                    if (now - lastCapacityWarnAt >= CAPACITY_WARN_INTERVAL_MS) {
                        log.warn("Rate limiter reached its {} key capacity", maxTrackedKeys);
                        lastCapacityWarnAt = now;
                    }
                    return false;
                }
                bySize.remove(largest);
                largest.windows.pollLastEntry();
                if (largest.windows.isEmpty()) accounts.remove(largest.owner);
                else bySize.add(largest);
                trackedKeys--;
            }
            if (account == null) {
                account = new Account(key.owner());
                accounts.put(account.owner, account);
            }
            bySize.remove(account);
            window = new KeyWindow();
            // Reclaim subject keys before an IP's aggregate guard.
            if (key.subject() == null) account.windows.putFirst(key, window);
            else account.windows.putLast(key, window);
            bySize.add(account);
            trackedKeys++;
        }
        window.windowMs = Math.max(1L, windowMs);
        window.prune(now);
        if (window.timestamps.size() >= limit) return false;
        window.timestamps.addLast(now);
        return true;
    }

    synchronized int trackedKeyCount() {
        return trackedKeys;
    }

    synchronized long sweepCount() {
        return sweeps;
    }

    private void sweep(long now) {
        lastSweepAt = now;
        sweeps++;
        var owners = accounts.values().iterator();
        while (owners.hasNext()) {
            var account = owners.next();
            var windows = account.windows.values().iterator();
            boolean changed = false;
            while (windows.hasNext()) {
                var window = windows.next();
                if (window.timestamps.getLast() > now - window.windowMs) continue;
                if (!changed) bySize.remove(account); // Before its sort key changes.
                changed = true;
                windows.remove();
                trackedKeys--;
            }
            if (account.windows.isEmpty()) owners.remove();
            else if (changed) bySize.add(account);
        }
    }

    private static final class Account {
        final String owner;
        final LinkedHashMap<Key, KeyWindow> windows = new LinkedHashMap<>();

        Account(String owner) {
            this.owner = owner;
        }
    }

    private static final class KeyWindow {
        final ArrayDeque<Long> timestamps = new ArrayDeque<>();
        long windowMs;

        void prune(long now) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= now - windowMs) timestamps.removeFirst();
        }
    }
}
