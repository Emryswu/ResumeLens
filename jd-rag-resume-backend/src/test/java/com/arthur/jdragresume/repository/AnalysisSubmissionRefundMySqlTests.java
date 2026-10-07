package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AnalysisSubmissionLog;
import com.arthur.jdragresume.entity.AnalysisSubmissionRefund;
import com.arthur.jdragresume.entity.AppUser;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL：V9 建出的退还表、唯一约束，以及限流用的「窗口内已退还提交」计数（JPQL 关联查询）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AnalysisSubmissionRefundMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_refund_test")
            .withUsername("jd_test")
            .withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.jwt.secret", () -> "repository-test-secret-at-least-32-bytes");
    }

    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private AnalysisSubmissionLogRepository submissionLogRepository;
    @Autowired
    private AnalysisSubmissionRefundRepository refundRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private AppUser user;
    private AppUser otherUser;

    @BeforeEach
    void createUsers() {
        user = saveUser();
        otherUser = saveUser();
    }

    @Test
    void countsOnlyThisUsersRefundedSubmissionsInsideTheWindow() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        AnalysisSubmissionLog recentRefunded = saveSubmission(user, LocalDateTime.now().minusMinutes(1));
        saveSubmission(user, LocalDateTime.now().minusMinutes(2));
        AnalysisSubmissionLog oldRefunded = saveSubmission(user, LocalDateTime.now().minusMinutes(30));
        AnalysisSubmissionLog otherUsersRefunded = saveSubmission(otherUser, LocalDateTime.now().minusMinutes(1));
        refund(recentRefunded);
        refund(oldRefunded);
        refund(otherUsersRefunded);

        assertEquals(2, submissionLogRepository.countByUser_IdAndCreatedAtAfter(user.getId(), cutoff));
        assertEquals(1, refundRepository.countRefundedSubmissions(user.getId(), cutoff));
        assertTrue(refundRepository.existsBySubmissionLogId(recentRefunded.getId()));
    }

    @Test
    void aSubmissionCanBeRefundedOnlyOnce() {
        AnalysisSubmissionLog submission = saveSubmission(user, LocalDateTime.now());
        refund(submission);

        assertThrows(DataIntegrityViolationException.class, () -> refund(submission));
    }

    @Test
    void analysisHistoryGetsTheSubmissionLinkColumn() {
        Integer columns = jdbcTemplate.queryForObject(
                """
                        select count(*) from information_schema.columns
                        where table_schema = database() and table_name = 'analysis_history'
                          and column_name = 'submission_log_id' and is_nullable = 'YES'
                        """,
                Integer.class
        );

        assertEquals(1, columns);
    }

    private AppUser saveUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        AppUser created = new AppUser();
        created.setUsername("refund-" + suffix);
        created.setEmail("refund-" + suffix + "@example.com");
        created.setDisplayName("Refund Test");
        created.setPasswordHash("not-used");
        return appUserRepository.saveAndFlush(created);
    }

    private AnalysisSubmissionLog saveSubmission(AppUser owner, LocalDateTime createdAt) {
        AnalysisSubmissionLog submission = new AnalysisSubmissionLog();
        submission.setUser(owner);
        submission = submissionLogRepository.saveAndFlush(submission);
        // created_at is set by @PrePersist and not updatable through JPA; move it for the window test.
        // Bind through Hibernate, not JdbcTemplate: hibernate.jdbc.time_zone shifts LocalDateTime parameters,
        // and the repository query under test is shifted the same way. A raw JDBC write is not, so on a
        // runner whose JVM zone differs from that setting (UTC on CI) the rows would land outside the window.
        entityManager.createNativeQuery("update analysis_submission_log set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", submission.getId())
                .executeUpdate();
        return submission;
    }

    private void refund(AnalysisSubmissionLog submission) {
        AnalysisSubmissionRefund refund = new AnalysisSubmissionRefund();
        refund.setSubmissionLogId(submission.getId());
        refund.setReason("AI_RESPONSE_CITATION_INVALID");
        refundRepository.saveAndFlush(refund);
    }
}
