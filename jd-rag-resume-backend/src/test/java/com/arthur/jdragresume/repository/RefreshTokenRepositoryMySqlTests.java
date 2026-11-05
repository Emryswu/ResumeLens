package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.dto.analysis.AnalysisHistorySummaryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.RefreshToken;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.service.ContentFingerprints;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManager;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MySQL repository slice：让 Hibernate 解析 JPQL、Flyway 安装真实索引，并验证
 * refresh token 清理与当前输入版本分析查询的语义；替身仓库单测覆盖不到这些边界。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class RefreshTokenRepositoryMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_refresh_token_test")
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
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private ResumeRepository resumeRepository;
    @Autowired
    private JobDescriptionRepository jobDescriptionRepository;
    @Autowired
    private AnalysisHistoryRepository analysisHistoryRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private AppUser user;

    @BeforeEach
    void createOwner() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        user = new AppUser();
        user.setUsername("cleanup-" + suffix);
        user.setEmail("cleanup-" + suffix + "@example.com");
        user.setDisplayName("Cleanup Test");
        user.setPasswordHash("not-used");
        user = appUserRepository.saveAndFlush(user);
    }

    @Test
    void cleanupQuerySelectsExpiredAndRetiredRowsOnly() {
        LocalDateTime now = LocalDateTime.now();
        RefreshToken expired = saveToken("expired", now.minusDays(2), null);
        RefreshToken retired = saveToken("retired", now.minusHours(1), now.minusDays(8));
        RefreshToken active = saveToken("active", now.plusDays(7), null);
        RefreshToken revokedButUnexpired = saveToken("unexpired", now.plusDays(30), now.minusDays(8));
        RefreshToken recentlyRevokedAndExpired = saveToken("recently-revoked", now.minusDays(2), now.minusDays(2));

        List<Long> candidates = refreshTokenRepository.findCleanupCandidateIds(
                now.minusDays(1),
                now.minusDays(7),
                now,
                PageRequest.of(0, 500)
        );
        refreshTokenRepository.deleteAllByIdInBatch(candidates);

        assertEquals(2, candidates.size());
        assertTrue(candidates.contains(expired.getId()));
        assertTrue(candidates.contains(retired.getId()));
        assertFalse(refreshTokenRepository.existsById(expired.getId()));
        assertFalse(refreshTokenRepository.existsById(retired.getId()));
        // 未过期的一律留着，即使已经撤销——撤销行要活过整个 refresh 有效期，
        // 否则重放检测就没有可比对的记录了。
        assertTrue(refreshTokenRepository.existsById(active.getId()));
        assertTrue(refreshTokenRepository.existsById(revokedButUnexpired.getId()));
        // 已过期但刚撤销不久：仍在重放检测窗口内，不能删。
        assertTrue(refreshTokenRepository.existsById(recentlyRevokedAndExpired.getId()));
    }

    @Test
    void cleanupQueryHonorsTheRequestedBatchSize() {
        LocalDateTime now = LocalDateTime.now();
        saveToken("expired-a", now.minusDays(2), null);
        saveToken("expired-b", now.minusDays(3), null);
        saveToken("expired-c", now.minusDays(4), null);

        List<Long> firstBatch = refreshTokenRepository.findCleanupCandidateIds(
                now.minusDays(1),
                now.minusDays(7),
                now,
                PageRequest.of(0, 2)
        );

        assertEquals(2, firstBatch.size());
    }

    @Test
    void latestAnalysisQueryReturnsOnlyTheNewestCurrentInputVersion() {
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("Backend resume");
        resume.setCandidateName("Arthur");
        resume.setRawText("Java Spring Boot");
        resume = resumeRepository.saveAndFlush(resume);

        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle("Java engineer");
        job.setCompanyName("Example Inc");
        job.setDescription("Build backend APIs");
        job.setRequirements("Java");
        job.setContentFingerprint(ContentFingerprints.job(job));
        job = jobDescriptionRepository.saveAndFlush(job);

        String resumeFingerprint = ContentFingerprints.resume(resume);
        String jobFingerprint = ContentFingerprints.job(job);
        AnalysisHistory current = saveAnalysis(
                resume,
                job,
                resumeFingerprint,
                jobFingerprint,
                new BigDecimal("88.00")
        );
        saveAnalysis(resume, job, resumeFingerprint, "stale-job-version", new BigDecimal("99.00"));

        List<AnalysisHistorySummaryResponse> latest = analysisHistoryRepository
                .findLatestCurrentForEachJobByUserIdAndResumeId(
                        user.getId(),
                        resume.getId(),
                        resumeFingerprint
                );

        assertEquals(1, latest.size());
        assertEquals(current.getId(), latest.getFirst().id());
        assertEquals(new BigDecimal("88.00"), latest.getFirst().matchScore());
        assertEquals(
                current.getId(),
                analysisHistoryRepository
                        .findFirstByUser_IdAndResume_IdAndJobDescription_IdAndResumeFingerprintAndJobFingerprintOrderByIdDesc(
                                user.getId(),
                                resume.getId(),
                                job.getId(),
                                resumeFingerprint,
                                jobFingerprint
                        )
                        .orElseThrow()
                        .getId()
        );
    }

    @Test
    void latestAnalysisCompositeIndexIsInstalledByFlyway() {
        List<String> columns = jdbcTemplate.queryForList(
                """
                        select column_name
                        from information_schema.statistics
                        where table_schema = database()
                          and table_name = 'analysis_history'
                          and index_name = 'idx_analysis_latest'
                        order by seq_in_index
                        """,
                String.class
        );

        assertEquals(List.of("user_id", "resume_id", "job_description_id", "id"), columns);
    }

    @Test
    void usernameConflictLookupUsesTheDatabaseCaseInsensitiveCollation() {
        assertTrue(appUserRepository.existsByUsername(user.getUsername().toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void revokeActiveForUserRevokesOnlyThatUsersLiveTokens() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 16, 0);
        RefreshToken active = saveToken("password-reset", now.plusDays(7), null);
        RefreshToken previouslyRevoked = saveToken("previous", now.plusDays(7), now.minusDays(1));
        AppUser otherOwner = new AppUser();
        String otherSuffix = UUID.randomUUID().toString().replace("-", "");
        otherOwner.setUsername("other-" + otherSuffix);
        otherOwner.setEmail("other-" + otherSuffix + "@example.com");
        otherOwner.setDisplayName("Other");
        otherOwner.setPasswordHash("not-used");
        otherOwner = appUserRepository.saveAndFlush(otherOwner);
        RefreshToken otherToken = new RefreshToken();
        otherToken.setUser(otherOwner);
        otherToken.setTokenHash(UUID.randomUUID().toString().replace("-", ""));
        otherToken.setFamilyId(UUID.randomUUID().toString());
        otherToken.setExpiresAt(now.plusDays(7));
        otherToken = refreshTokenRepository.saveAndFlush(otherToken);

        int affected = refreshTokenRepository.revokeActiveForUser(user.getId(), now, "PASSWORD_CHANGED");
        assertEquals(1, affected);
        entityManager.clear();

        RefreshToken changed = refreshTokenRepository.findById(active.getId()).orElseThrow();
        assertEquals(now, changed.getRevokedAt());
        assertEquals("PASSWORD_CHANGED", changed.getReplacedByHash());
        assertEquals(now.minusDays(1),
                refreshTokenRepository.findById(previouslyRevoked.getId()).orElseThrow().getRevokedAt());
        assertEquals(null, refreshTokenRepository.findById(otherToken.getId()).orElseThrow().getRevokedAt());
    }

    private RefreshToken saveToken(String label, LocalDateTime expiresAt, LocalDateTime revokedAt) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash((label + suffix).substring(0, 32));
        token.setFamilyId(UUID.randomUUID().toString());
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);
        return refreshTokenRepository.saveAndFlush(token);
    }

    private AnalysisHistory saveAnalysis(
            Resume resume,
            JobDescription job,
            String resumeFingerprint,
            String jobFingerprint,
            BigDecimal score
    ) {
        AnalysisHistory history = new AnalysisHistory();
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(job);
        history.setResumeFingerprint(resumeFingerprint);
        history.setJobFingerprint(jobFingerprint);
        history.setMatchScore(score);
        history.setStatus(AnalysisStatus.COMPLETED);
        history.setSummary("test analysis");
        return analysisHistoryRepository.saveAndFlush(history);
    }
}
