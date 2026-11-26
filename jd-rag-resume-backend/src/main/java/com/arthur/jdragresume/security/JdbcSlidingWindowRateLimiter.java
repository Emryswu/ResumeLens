package com.arthur.jdragresume.security;

import com.arthur.jdragresume.common.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * MySQL-backed sliding-window rate limiter for multi-instance deployments without external Redis.
 * Uses a bucket anchor row ({@code rate_limit_buckets}) with {@code FOR UPDATE} to avoid InnoDB
 * gap-lock deadlocks on empty buckets while keeping {@code tryAcquire} and {@code release} atomic.
 */
public class JdbcSlidingWindowRateLimiter extends SlidingWindowRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(JdbcSlidingWindowRateLimiter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final LongSupplier clock;
    private final int cleanupBatchSize;

    public JdbcSlidingWindowRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, System::currentTimeMillis, 1000);
    }

    public JdbcSlidingWindowRateLimiter(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            LongSupplier clock,
            int cleanupBatchSize
    ) {
        super(clock);
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.cleanupBatchSize = Math.max(10, cleanupBatchSize);
    }

    @Override
    public boolean tryAcquire(Key key, int limit, long windowMs) {
        if (limit <= 0) {
            return false;
        }
        long now = clock.getAsLong();
        long safeWindowMs = Math.max(1L, windowMs);
        long windowStart = now - safeWindowMs;
        long expiresAt = now + safeWindowMs;
        String bucketKey = encodeKey(key);

        Boolean acquired = transactions.execute(status -> {
            jdbc.update(
                    "INSERT INTO rate_limit_buckets(bucket_key, updated_at_ms) VALUES (?, ?) "
                            + "ON DUPLICATE KEY UPDATE bucket_key = bucket_key",
                    bucketKey, now);
            jdbc.queryForObject(
                    "SELECT bucket_key FROM rate_limit_buckets WHERE bucket_key = ? FOR UPDATE",
                    String.class, bucketKey);
            jdbc.update(
                    "DELETE FROM rate_limit_entries WHERE bucket_key = ? AND hit_at_ms <= ?",
                    bucketKey, windowStart);
            Integer active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rate_limit_entries WHERE bucket_key = ? AND hit_at_ms > ?",
                    Integer.class, bucketKey, windowStart);
            if (active != null && active >= limit) {
                return Boolean.FALSE;
            }
            jdbc.update(
                    "INSERT INTO rate_limit_entries(bucket_key, hit_at_ms, expires_at_ms) VALUES (?, ?, ?)",
                    bucketKey, now, expiresAt);
            jdbc.update(
                    "UPDATE rate_limit_buckets SET updated_at_ms = ? WHERE bucket_key = ?",
                    now, bucketKey);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void release(Key key) {
        String bucketKey = encodeKey(key);
        transactions.executeWithoutResult(status -> {
            List<String> locked = jdbc.query(
                    "SELECT bucket_key FROM rate_limit_buckets WHERE bucket_key = ? FOR UPDATE",
                    (rs, n) -> rs.getString(1), bucketKey);
            if (locked.isEmpty()) {
                return;
            }
            List<Long> latest = jdbc.query(
                    "SELECT id FROM rate_limit_entries WHERE bucket_key = ? ORDER BY hit_at_ms DESC, id DESC LIMIT 1",
                    (rs, n) -> rs.getLong(1), bucketKey);
            if (!latest.isEmpty()) {
                jdbc.update("DELETE FROM rate_limit_entries WHERE id = ?", latest.getFirst());
            }
        });
    }

    public int cleanupExpired() {
        long now = clock.getAsLong();
        int deletedEntries = jdbc.update(
                "DELETE FROM rate_limit_entries WHERE expires_at_ms <= ? LIMIT ?",
                now, cleanupBatchSize);
        long idleCutoff = now - 86_400_000L;
        List<String> staleBuckets = jdbc.query(
                "SELECT bucket_key FROM rate_limit_buckets WHERE updated_at_ms <= ? LIMIT ?",
                (rs, n) -> rs.getString(1), idleCutoff, cleanupBatchSize);
        for (String bucketKey : staleBuckets) {
            transactions.executeWithoutResult(status -> {
                List<String> locked = jdbc.query(
                        "SELECT bucket_key FROM rate_limit_buckets WHERE bucket_key = ? AND updated_at_ms <= ? FOR UPDATE",
                        (rs, n) -> rs.getString(1), bucketKey, idleCutoff);
                if (locked.isEmpty()) {
                    return;
                }
                Integer remaining = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rate_limit_entries WHERE bucket_key = ?",
                        Integer.class, bucketKey);
                if (remaining == null || remaining == 0) {
                    jdbc.update("DELETE FROM rate_limit_buckets WHERE bucket_key = ?", bucketKey);
                }
            });
        }
        return deletedEntries;
    }

    static String encodeKey(Key key) {
        String scope = key.scope() == null ? "" : key.scope();
        String safeScope = scope.length() <= 40 ? scope : scope.substring(0, 40);
        String raw = scope + "\0" + (key.ip() == null ? "" : key.ip()) + "\0" + (key.subject() == null ? "" : key.subject());
        return safeScope + ":" + sha256(raw);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "app.rate-limit.mode", havingValue = "jdbc")
    public static class JdbcRateLimitConfiguration {
        private static final Logger cleanupLog = LoggerFactory.getLogger(JdbcRateLimitConfiguration.class);
        private volatile JdbcSlidingWindowRateLimiter activeLimiter;

        @Bean
        @Primary
        public SlidingWindowRateLimiter jdbcSlidingWindowRateLimiter(
                JdbcTemplate jdbc,
                PlatformTransactionManager transactionManager,
                RateLimitProperties properties
        ) {
            JdbcSlidingWindowRateLimiter limiter = new JdbcSlidingWindowRateLimiter(
                    jdbc, transactionManager, System::currentTimeMillis, properties.getCleanupBatchSize());
            this.activeLimiter = limiter;
            return limiter;
        }

        @Scheduled(fixedDelayString = "${app.rate-limit.cleanup-interval-ms:60000}")
        public void scheduledCleanup() {
            JdbcSlidingWindowRateLimiter limiter = this.activeLimiter;
            if (limiter == null) {
                return;
            }
            try {
                limiter.cleanupExpired();
            } catch (RuntimeException ex) {
                cleanupLog.warn("Rate limit cleanup failed: {}", LogSafe.describe(ex));
            }
        }
    }
}
