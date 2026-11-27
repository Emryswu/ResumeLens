package com.arthur.jdragresume.security;

import com.arthur.jdragresume.security.SlidingWindowRateLimiter.Key;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcSlidingWindowRateLimiterMySqlTests {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("rate_limit_capacity_test")
            .withUsername("jd_test")
            .withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @Test
    void concurrentInstancesRespectCapacityAndCleanupReleasesSlots() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V25__rate_limit_entries.sql")).execute(source);
        // The migration must count buckets already persisted by the previous version.
        jdbc.update("INSERT INTO rate_limit_buckets(bucket_key, updated_at_ms) VALUES ('old', 0)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V26__rate_limit_bucket_capacity.sql")).execute(source);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT tracked_buckets FROM rate_limit_capacity WHERE id = 1", Long.class));
        AtomicLong now = new AtomicLong(1_000_000L);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(source);
        var a = new JdbcSlidingWindowRateLimiter(jdbc, tx, now::get, 100, 4);
        var b = new JdbcSlidingWindowRateLimiter(jdbc, tx, now::get, 100, 4);
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    return (n % 2 == 0 ? a : b).tryAcquire(
                            new Key("login", "192.0.2." + n, "user"), 3, 1_000L);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> future : futures) if (future.get(20, TimeUnit.SECONDS)) accepted++;
            assertEquals(3, accepted);
        }
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_buckets", Integer.class));
        assertEquals(4L, jdbc.queryForObject(
                "SELECT tracked_buckets FROM rate_limit_capacity WHERE id = 1", Long.class));
        now.addAndGet(1_001L);
        assertEquals(3, a.cleanupExpired());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_buckets", Integer.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT tracked_buckets FROM rate_limit_capacity WHERE id = 1", Long.class));
        assertTrue(b.tryAcquire(new Key("login", "192.0.2.100", "new"), 3, 1_000L));
    }
}
