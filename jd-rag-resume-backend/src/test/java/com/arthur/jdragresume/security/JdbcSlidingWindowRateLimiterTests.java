package com.arthur.jdragresume.security;

import com.arthur.jdragresume.security.SlidingWindowRateLimiter.Key;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcSlidingWindowRateLimiterTests {
    private static final PlatformTransactionManager NOOP_TX = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) { }

        @Override
        public void rollback(TransactionStatus status) { }
    };

    private record Entry(long id, String bucketKey, long hitAtMs, long expiresAtMs) {}

    @Test
    void sharesWindowAcrossInstancesReleasesUserBucketAndCleansUpExpiredRows() {
        AtomicLong now = new AtomicLong(1_000_000L);
        Map<String, Long> buckets = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        AtomicLong ids = new AtomicLong();

        JdbcTemplate jdbc = mockJdbc(buckets, entries, ids);
        JdbcSlidingWindowRateLimiter nodeA = new JdbcSlidingWindowRateLimiter(jdbc, NOOP_TX, now::get, 100);
        JdbcSlidingWindowRateLimiter nodeB = new JdbcSlidingWindowRateLimiter(jdbc, NOOP_TX, now::get, 100);

        Key userKey = new Key("profile-password", null, "user:7");
        Key ipKey = new Key("profile-password", "192.0.2.10", null);

        // Two separate nodes share the exact same MySQL window.
        assertTrue(nodeA.tryAcquire(userKey, 2, 10_000L));
        assertTrue(nodeB.tryAcquire(userKey, 2, 10_000L));
        assertFalse(nodeA.tryAcquire(userKey, 2, 10_000L));

        // Successful password check on node B refunds the latest user-bucket hit only.
        assertTrue(nodeA.tryAcquire(ipKey, 1, 10_000L));
        assertFalse(nodeB.tryAcquire(ipKey, 1, 10_000L));
        nodeB.release(userKey);
        assertTrue(nodeA.tryAcquire(userKey, 2, 10_000L));
        assertFalse(nodeB.tryAcquire(userKey, 2, 10_000L));
        assertFalse(nodeA.tryAcquire(ipKey, 1, 10_000L));

        // Sliding forward beyond windowMs admits again and cleanupExpired purges stale rows.
        now.addAndGet(10_001L);
        assertEquals(3, nodeA.cleanupExpired());
        assertTrue(entries.isEmpty());
        assertTrue(nodeB.tryAcquire(userKey, 2, 10_000L));
    }

    @Test
    void rejectsNewBucketsAtCapacityButExistingBucketsContinueAndCleanupRestoresAdmission() {
        AtomicLong now = new AtomicLong(1_000_000L);
        Map<String, Long> buckets = new HashMap<>();
        List<Entry> entries = new ArrayList<>();
        JdbcTemplate jdbc = mockJdbc(buckets, entries, new AtomicLong());
        JdbcSlidingWindowRateLimiter a = new JdbcSlidingWindowRateLimiter(jdbc, NOOP_TX, now::get, 100, 2);
        JdbcSlidingWindowRateLimiter b = new JdbcSlidingWindowRateLimiter(jdbc, NOOP_TX, now::get, 100, 2);
        Key first = new Key("login", "192.0.2.1", "alice");
        Key second = new Key("login", "192.0.2.2", "bob");
        Key third = new Key("login", "192.0.2.3", "charlie");
        assertTrue(a.tryAcquire(first, 3, 1_000));
        assertTrue(b.tryAcquire(second, 3, 1_000));
        assertFalse(b.tryAcquire(third, 3, 1_000));
        assertEquals(2, buckets.size());
        assertTrue(b.tryAcquire(first, 3, 1_000));
        now.addAndGet(1_001);
        assertEquals(3, a.cleanupExpired());
        assertTrue(buckets.isEmpty());
        assertTrue(b.tryAcquire(third, 3, 1_000));
    }

    @Test
    void encodeKeyBoundsLengthAndSeparatesComponentsWithoutCollision() {
        String hugeSubject = "a".repeat(5_000);
        String longKey = JdbcSlidingWindowRateLimiter.encodeKey(new Key("login", "192.0.2.1", hugeSubject));
        assertTrue(longKey.length() <= 255);

        String k1 = JdbcSlidingWindowRateLimiter.encodeKey(new Key("login", "1.2.3.4", "alice"));
        String k2 = JdbcSlidingWindowRateLimiter.encodeKey(new Key("login", "1.2.3", "4alice"));
        assertNotEquals(k1, k2);
    }

    @SuppressWarnings("unchecked")
    private static JdbcTemplate mockJdbc(Map<String, Long> buckets, List<Entry> entries, AtomicLong ids) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, String> tokens = new HashMap<>();
        AtomicLong capacity = new AtomicLong();
        when(jdbc.update(contains("INSERT INTO rate_limit_buckets"), anyString(), anyLong(), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(1);
                    long ts = inv.getArgument(2);
                    if (buckets.putIfAbsent(key, ts) == null) {
                        tokens.put(key, inv.getArgument(3));
                        return 1;
                    }
                    return 0;
                });
        when(jdbc.queryForObject(contains("SELECT creation_token FROM rate_limit_buckets"), eq(String.class), anyString()))
                .thenAnswer(inv -> tokens.get(inv.getArgument(2)));
        when(jdbc.update(contains("tracked_buckets = tracked_buckets + 1"), anyInt()))
                .thenAnswer(inv -> {
                    int max = inv.getArgument(1);
                    if (capacity.get() >= max) return 0;
                    capacity.incrementAndGet();
                    return 1;
                });
        when(jdbc.update(contains("tracked_buckets = tracked_buckets - 1")))
                .thenAnswer(inv -> { capacity.decrementAndGet(); return 1; });
        when(jdbc.update(eq("DELETE FROM rate_limit_buckets WHERE bucket_key = ?"), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(1);
                    tokens.remove(key);
                    return buckets.remove(key) != null ? 1 : 0;
                });
        when(jdbc.query(contains("WHERE NOT EXISTS (SELECT 1 FROM rate_limit_entries"), any(RowMapper.class), anyInt()))
                .thenAnswer(inv -> buckets.keySet().stream()
                        .filter(key -> entries.stream().noneMatch(entry -> entry.bucketKey().equals(key)))
                        .limit((Integer) inv.getArgument(2)).toList());
        when(jdbc.update(contains("DELETE FROM rate_limit_entries WHERE bucket_key = ? AND hit_at_ms <="),
                anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            long cutoff = inv.getArgument(2);
            int before = entries.size();
            entries.removeIf(e -> e.bucketKey().equals(key) && e.hitAtMs() <= cutoff);
            return before - entries.size();
        });
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM rate_limit_entries"),
                eq(Integer.class), anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(2);
            long cutoff = inv.getArgument(3);
            return (int) entries.stream().filter(e -> e.bucketKey().equals(key) && e.hitAtMs() > cutoff).count();
        });
        when(jdbc.update(contains("INSERT INTO rate_limit_entries"), anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    entries.add(new Entry(ids.incrementAndGet(), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
                    return 1;
                });
        when(jdbc.update(contains("UPDATE rate_limit_buckets SET updated_at_ms"), anyLong(), anyString()))
                .thenAnswer(inv -> {
                    buckets.put(inv.getArgument(2), inv.getArgument(1));
                    return 1;
                });
        when(jdbc.query(contains("SELECT bucket_key FROM rate_limit_buckets"), any(RowMapper.class), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(2);
                    return buckets.containsKey(key) ? List.of(key) : List.of();
                });
        when(jdbc.query(contains("SELECT id FROM rate_limit_entries"), any(RowMapper.class), anyString()))
                .thenAnswer(inv -> {
                    String key = inv.getArgument(2);
                    RowMapper<Long> mapper = inv.getArgument(1);
                    return entries.stream()
                            .filter(e -> e.bucketKey().equals(key))
                            .max(Comparator.comparingLong(Entry::hitAtMs).thenComparingLong(Entry::id))
                            .map(e -> {
                                try {
                                    ResultSet rs = mock(ResultSet.class);
                                    when(rs.getLong(1)).thenReturn(e.id());
                                    return List.of(mapper.mapRow(rs, 0));
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            }).orElse(List.of());
                });
        when(jdbc.update(eq("DELETE FROM rate_limit_entries WHERE id = ?"), anyLong()))
                .thenAnswer(inv -> {
                    long id = inv.getArgument(1);
                    return entries.removeIf(e -> e.id() == id) ? 1 : 0;
                });
        when(jdbc.update(contains("DELETE FROM rate_limit_entries WHERE expires_at_ms <="), anyLong(), anyInt()))
                .thenAnswer(inv -> {
                    long cutoff = inv.getArgument(1);
                    int before = entries.size();
                    entries.removeIf(e -> e.expiresAtMs() <= cutoff);
                    return before - entries.size();
                });
        when(jdbc.update(contains("DELETE FROM rate_limit_buckets WHERE updated_at_ms <="), anyLong(), anyInt()))
                .thenAnswer(inv -> 0);
        return jdbc;
    }
}
