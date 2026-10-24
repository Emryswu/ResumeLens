package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.exception.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class BossPageBudgetMySqlTests {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("boss_budget_test").withUsername("jd_test").withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");
    private JdbcTemplate jdbc;
    private BossPageBudget budget;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T17:30:00Z"), ZoneId.of("Asia/Shanghai"));
    private final CollectionSession session = new CollectionSession(1L, "42", "task", "call", CollectorMode.AUTO, Long.MAX_VALUE, event -> {});

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).target("18").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM app_user");
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at,display_name,email,password_hash,username) VALUES(1,NOW(),NOW(),'User','budget@test.local','hash','budget')");
        budget = new BossPageBudget(jdbc, new DataSourceTransactionManager(source), clock);
    }

    @Test void bothBrowsersShareTheDailyCapAndOnlyDefiniteUnusedPagesAreReleased() {
        budget.reserve(session, "chrome", 3);
        assertEquals(3, budget.reserve(session, "chrome", 3).pages(), "an unfinished reservation is idempotent");
        budget.reserve(session, "patchright", 27);
        assertThrows(BusinessException.class, () -> budget.reserve(session, "over", 1));
        assertEquals(2, budget.finish(session, "chrome", 1));
        assertEquals(2, budget.finish(session, "chrome", 1), "a duplicate final report cannot refund twice");
        assertThrows(BusinessException.class, () -> budget.reserve(session, "chrome", 3));
        assertEquals(0, budget.reserve(session, "fallback", 2).dailyPagesLeft());
        assertThrows(BusinessException.class, () -> budget.finish(session, "fallback", 3));
        jdbc.update("DELETE FROM app_user WHERE id=1");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM boss_page_reservation", Integer.class));
    }

    @Test void concurrentReservationsDoNotOverspendAndPreviousDaysDoNotConsumeToday() throws Exception {
        jdbc.update("INSERT INTO boss_daily_budget VALUES(1,?,30)", LocalDate.now(clock).minusDays(1));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> budget.reserve(session, "a", 20));
            var b = pool.submit(() -> budget.reserve(session, "b", 20));
            int admitted = 0;
            for (var result : java.util.List.of(a, b)) {
                try { result.get(); admitted++; }
                catch (java.util.concurrent.ExecutionException ex) { assertInstanceOf(BusinessException.class, ex.getCause()); }
            }
            assertEquals(1, admitted);
        }
        assertEquals(20, jdbc.queryForObject("SELECT pages FROM boss_daily_budget WHERE user_id=1 AND budget_date=?", Integer.class, LocalDate.now(clock)));
    }
}
