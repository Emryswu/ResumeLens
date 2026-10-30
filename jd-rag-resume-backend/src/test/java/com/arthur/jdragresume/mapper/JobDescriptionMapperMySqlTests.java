package com.arthur.jdragresume.mapper;

import com.arthur.jdragresume.dto.JobListItem;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.CollectBossJobsTool;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.SearchJobsTool;
import com.arthur.jdragresume.collect.CollectedListing;
import com.arthur.jdragresume.collect.CollectionResult;
import com.arthur.jdragresume.collect.JobCollectionHistory;
import com.arthur.jdragresume.collect.JobCollector;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.SemanticEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.arthur.jdragresume.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.*;

/**
 * 真实 MySQL + 真实 MyBatis XML：验证岗位查询的排序是全序的（created_at DESC, id DESC），
 * created_at 相同的记录按 id 倒序、翻页时不重不漏。替身 mapper 覆盖不到 SQL 本身。
 */
@DataJpaTest
@ImportAutoConfiguration(MybatisAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class JobDescriptionMapperMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_job_query_test")
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
    private JobDescriptionMapper jobDescriptionMapper;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AppUser owner;
    private AppUser otherUser;

    @BeforeEach
    void createUsers() {
        owner = saveUser("job-query-owner");
        otherUser = saveUser("job-query-other");
    }

    @Test
    void recordsWithTheSameCreatedAtAreOrderedByIdDescending() {
        LocalDateTime older = LocalDateTime.of(2026, 9, 1, 10, 0);
        LocalDateTime sameMoment = LocalDateTime.of(2026, 9, 2, 10, 0);

        long oldest = insertJob(owner, "Oldest", older);
        long tieA = insertJob(owner, "Tie A", sameMoment);
        long tieB = insertJob(owner, "Tie B", sameMoment);
        long tieC = insertJob(owner, "Tie C", sameMoment);
        insertJob(otherUser, "Someone else's job", sameMoment);

        List<Long> ids = idsOf(jobDescriptionMapper.searchJobs(owner.getId(), null, null, 0, 50));

        // 同一时刻的三条按 id 倒序排在前面，更早的那条在最后；别人的岗位不出现
        assertEquals(List.of(tieC, tieB, tieA, oldest), ids);
    }

    @Test
    void paginatingThroughTiedTimestampsNeitherRepeatsNorSkipsRecords() {
        LocalDateTime sameMoment = LocalDateTime.of(2026, 9, 3, 9, 30);
        List<Long> inserted = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            inserted.add(insertJob(owner, "Tie " + i, sameMoment));
        }

        List<Long> fullList = idsOf(jobDescriptionMapper.searchJobs(owner.getId(), null, null, 0, 50));
        List<Long> paged = new ArrayList<>();
        for (int page = 0; page < 4; page++) {
            paged.addAll(idsOf(jobDescriptionMapper.searchJobs(owner.getId(), null, null, (long) page * 2, 2)));
        }

        List<Long> expected = new ArrayList<>(inserted);
        expected.sort((a, b) -> Long.compare(b, a));
        assertEquals(expected, fullList);
        assertEquals(fullList, paged, "逐页拼起来必须和一次查全完全一致");
    }

    @Test
    void pageBeyondTheDataButWithinTheLimitReturnsAnEmptyList() {
        insertJob(owner, "Only job", LocalDateTime.of(2026, 9, 4, 8, 0));

        assertTrue(jobDescriptionMapper.searchJobs(owner.getId(), null, null, 9_990, 10).isEmpty());
    }

    private AppUser saveUser(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        AppUser user = new AppUser();
        user.setUsername(prefix + "-" + suffix);
        user.setEmail(prefix + "-" + suffix + "@example.com");
        user.setDisplayName("Job Query Test");
        user.setPasswordHash("not-used");
        return appUserRepository.saveAndFlush(user);
    }

    private long insertJob(AppUser user, String title, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO job_description (created_at, updated_at, company_name, description, title, user_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            Timestamp timestamp = Timestamp.valueOf(createdAt);
            statement.setTimestamp(1, timestamp);
            statement.setTimestamp(2, timestamp);
            statement.setString(3, "Test Co");
            statement.setString(4, "JD body");
            statement.setString(5, title);
            statement.setLong(6, user.getId());
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    private static List<Long> idsOf(List<JobListItem> items) {
        return items.stream().map(JobListItem::getId).toList();
    }

    @Autowired
    private com.arthur.jdragresume.repository.JobDescriptionRepository jobs;

    @Test
    void agentEmploymentTypeSearchAndCountsAreScopedToTheCurrentUser() {
        LocalDateTime now = LocalDateTime.now();
        long intern = insertJob(owner, "后端开发", now);
        long fullTime = insertJob(owner, "C_ Backend", now);
        long untyped = insertJob(owner, "助理", now);
        long foreign = insertJob(otherUser, "后端开发", now);
        jdbcTemplate.update("UPDATE job_description SET employment_type = ? WHERE id IN (?, ?)", "实习", intern, foreign);
        jdbcTemplate.update("UPDATE job_description SET employment_type = ? WHERE id = ?", "全职", fullTime);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        assertEquals(List.of(intern), jobs.searchForUser(owner.getId(), "", "实习", "", pageable)
                .stream().map(com.arthur.jdragresume.entity.JobDescription::getId).toList());
        assertEquals(List.of(fullTime), jobs.searchForUser(owner.getId(), "C_", "全职", "", pageable)
                .stream().map(com.arthur.jdragresume.entity.JobDescription::getId).toList());
        assertTrue(jobs.searchForUser(owner.getId(), "%", "", "", pageable).isEmpty(), "keyword is literal, not a LIKE pattern");
        var counts = jobs.countTypesForUser(owner.getId()).stream().collect(java.util.stream.Collectors.toMap(
                com.arthur.jdragresume.repository.JobDescriptionRepository.TypeCount::getEmploymentType,
                com.arthur.jdragresume.repository.JobDescriptionRepository.TypeCount::getTotal));
        assertEquals(java.util.Map.of("实习", 1L, "全职", 1L, "未标注", 1L), counts);
    }

    @Test
    void latestCollectionPersistsEmptyResultsAndUpdatesOnlyItsOwnersRow() {
        var users = org.mockito.Mockito.mock(com.arthur.jdragresume.security.CurrentUserService.class);
        org.mockito.Mockito.when(users.getCurrentUser()).thenReturn(owner);
        var history = new com.arthur.jdragresume.collect.JobCollectionHistory(jdbcTemplate, users);
        assertTrue(history.latest().isEmpty());
        var empty = new com.arthur.jdragresume.collect.CollectionResult(
                com.arthur.jdragresume.collect.CollectionResult.Outcome.COMPLETED, "NO_MORE_RESULTS", "没有更多结果", 1,
                List.of(), 0, null);
        history.record(new com.arthur.jdragresume.collect.CollectRequest("", "深圳", 2, "在校生"), empty);
        var restarted = new com.arthur.jdragresume.collect.JobCollectionHistory(jdbcTemplate, users);
        assertEquals("在校生", restarted.latest().orElseThrow().experience());
        history.record(new com.arthur.jdragresume.collect.CollectRequest("Python", "广州", 1, "应届生"), empty);
        assertEquals("Python", restarted.latest().orElseThrow().keyword());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM latest_job_collection WHERE user_id = ?", Integer.class, owner.getId()));
        org.mockito.Mockito.when(users.getCurrentUser()).thenReturn(otherUser);
        assertTrue(restarted.latest().isEmpty());
        history.record(new com.arthur.jdragresume.collect.CollectRequest("Java", "深圳", 1), empty);
        org.mockito.Mockito.when(users.getCurrentUser()).thenReturn(owner);
        assertEquals("Python", restarted.latest().orElseThrow().keyword());
    }

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    void studentCollectionCanBeSearchedAfterSavingAndRecollectingExistingJobs() {
        long known = insertJob(owner, "已保存的完整岗位", LocalDateTime.now());
        long unknown = insertJob(owner, "旧记录没有采集条件", LocalDateTime.now());
        long foreign = insertJob(otherUser, "别人的岗位", LocalDateTime.now());
        jdbcTemplate.update("UPDATE job_description SET source_platform='BOSS', source_job_id='known', employment_type='全职' WHERE id=?", known);
        jdbcTemplate.update("UPDATE job_description SET collection_experience='在校生' WHERE id=?", foreign);
        var users = mock(CurrentUserService.class);
        when(users.getCurrentUser()).thenReturn(owner);
        var service = new JobDescriptionService(jobs, users, appUserRepository,
                mock(SemanticEmbeddingService.class), null, 200, java.time.Clock.systemDefaultZone());
        var history = new JobCollectionHistory(jdbcTemplate, users);
        var collector = mock(JobCollector.class);
        when(collector.platform()).thenReturn("BOSS");
        var listings = List.of(
                new CollectedListing("摘要不能覆盖完整岗位", "公司", "深圳", "全职", "摘要", "本科",
                        "https://www.zhipin.com/job_detail/known.html", "known", "dom"),
                new CollectedListing("新采集岗位", "公司", "深圳", "全职", "摘要", "本科",
                        "https://www.zhipin.com/job_detail/fresh.html", "fresh", "dom"));
        var collected = new CollectionResult(CollectionResult.Outcome.COMPLETED, "MAX_PAGES", "完成", 1, listings, 0, null);
        when(collector.start(any(), any())).thenReturn(new JobCollector.Collected(collected));
        var collect = new CollectBossJobsTool(collector, service, history);
        var search = new SearchJobsTool(service, history);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var context = new AgentToolContext(owner);
        var student = mapper.createObjectNode().put("keyword", "Java 后端").put("city", "深圳").put("experience", "在校生");
        assertInstanceOf(DeferrableTool.Done.class, collect.begin(student, context));
        jobs.flush();
        entityManager.clear();
        var result = mapper.valueToTree(search.execute(mapper.createObjectNode().put("collectionExperience", "在校生"), context));
        assertEquals(2, result.path("total").asInt());
        for (var job : result.path("jobs")) {
            assertEquals("在校生", job.path("collectionExperience").asText());
            assertEquals("全职", job.path("employmentType").asText());
        }
        var full = jobs.findById(known).orElseThrow();
        assertEquals("JD body", full.getDescription());
        assertEquals("已保存的完整岗位", full.getTitle());
        assertEquals("在校生", full.getCollectionExperience());
        assertEquals(null, jobs.findById(unknown).orElseThrow().getCollectionExperience());

        // The field describes the latest collection; recollecting fills/replaces it on known jobs too.
        when(collector.start(any(), any())).thenReturn(new JobCollector.Collected(new CollectionResult(
                CollectionResult.Outcome.COMPLETED, "MAX_PAGES", "完成", 1, List.of(listings.getFirst()), 0, null)));
        collect.begin(student.deepCopy().put("experience", "应届生"), context);
        jobs.flush();
        entityManager.clear();
        var remaining = mapper.valueToTree(search.execute(mapper.createObjectNode().put("collectionExperience", "在校生"), context));
        assertEquals(1, remaining.path("total").asInt());
        var graduates = mapper.valueToTree(search.execute(mapper.createObjectNode().put("collectionExperience", "应届生"), context));
        assertEquals(known, graduates.path("jobs").path(0).path("jobId").asLong());
    }
}
