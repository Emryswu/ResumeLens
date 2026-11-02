package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真实 MySQL：分析记录搜索按报告显示的标题匹配（V23 的 job_title_snapshot，旧报告退回当前岗位标题）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class AnalysisHistorySearchMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_history_search_test")
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
    private AnalysisHistoryRepository analysisHistoryRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void searchMatchesTheTitleTheReportShowsAfterTheJobIsRenamed() {
        AppUser user = saveUser();
        JobDescription job = saveJob(user, "Java 后端");
        AnalysisHistory history = newHistory(user, job);
        history.captureJobSnapshot(job);
        analysisHistoryRepository.saveAndFlush(history);

        job.setTitle("Python 后端");
        entityManager.flush();
        entityManager.clear();

        assertEquals(List.of(history.getId()), search(user, "Java"));
        assertEquals(List.of(), search(user, "Python"));
    }

    @Test
    void reportsWithoutASnapshotStillMatchTheCurrentJobTitle() {
        AppUser user = saveUser();
        JobDescription job = saveJob(user, "Go 后端");
        AnalysisHistory history = analysisHistoryRepository.saveAndFlush(newHistory(user, job));
        entityManager.clear();

        assertEquals(List.of(history.getId()), search(user, "Go"));
    }

    private List<Long> search(AppUser user, String keyword) {
        return analysisHistoryRepository.searchByUserId(user.getId(), keyword, PageRequest.of(0, 10))
                .map(AnalysisHistory::getId).getContent();
    }

    private AnalysisHistory newHistory(AppUser user, JobDescription job) {
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("我的简历");
        resume.setCandidateName("测试");
        resume.setRawText("Java Spring Boot");
        entityManager.persist(resume);
        AnalysisHistory history = new AnalysisHistory();
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(job);
        history.setSummary("ok");
        return history;
    }

    private JobDescription saveJob(AppUser user, String title) {
        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle(title);
        job.setCompanyName("公司");
        job.setDescription("后端开发");
        entityManager.persist(job);
        entityManager.flush();
        return job;
    }

    private AppUser saveUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AppUser created = new AppUser();
        created.setUsername("search-" + suffix);
        created.setEmail("search-" + suffix + "@example.com");
        created.setDisplayName("Search Test");
        created.setPasswordHash("not-used");
        return appUserRepository.saveAndFlush(created);
    }
}
