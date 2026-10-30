package com.arthur.jdragresume.integration;

import com.arthur.jdragresume.controller.JobDescriptionController;
import com.arthur.jdragresume.controller.ResumeController;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.rag.LuceneVectorIndex;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
class ResumeDeleteCascadeMySqlTests {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withDatabaseName("jd_rag_resume_test")
            .withUsername("jd_test")
            .withPassword("jd_test_password")
            .withConfigurationOverride("mysql-9.7-conf");

    @Test
    void deletingAnalyzedResumeReturns204AndCascadesAnalysisAndChunks(@TempDir Path tempDir) throws Exception {
        DatabaseConfiguration database = migrateDatabase();

        try (Connection connection = DriverManager.getConnection(
                database.url(),
                database.username(),
                database.password()
        )) {
            InsertedRows rows = insertAnalyzedResume(connection);
            try {
                Resume resume = new Resume();
                resume.setUser(rows.user());
                resume.setTitle("Cascade test resume");
                resume.setCandidateName("Arthur");
                ReflectionTestUtils.setField(resume, "id", rows.resumeId());

                ResumeRepository resumeRepository = proxy(
                        ResumeRepository.class,
                        (ignored, method, args) -> switch (method.getName()) {
                            case "findByIdAndUserId" -> Optional.of(resume);
                            case "delete" -> {
                                executeDelete(connection, "DELETE FROM resume WHERE id = ?", rows.resumeId());
                                yield null;
                            }
                            case "toString" -> "ResumeRepositoryMySqlTestDouble";
                            default -> throw new UnsupportedOperationException(
                                    "Unexpected repository call: " + method.getName()
                            );
                        }
                );
                ResumeChunkRepository resumeChunkRepository = proxy(
                        ResumeChunkRepository.class,
                        (ignored, method, args) -> switch (method.getName()) {
                            case "deleteByResumeId" -> 0L;
                            case "toString" -> "ResumeChunkRepositoryMySqlTestDouble";
                            default -> throw new UnsupportedOperationException(
                                    "Unexpected repository call: " + method.getName()
                            );
                        }
                );
                CurrentUserService currentUserService = new FixedCurrentUserService(rows.user());

                try (LuceneVectorIndex vectorIndex = new LuceneVectorIndex(
                        new ObjectMapper(),
                        tempDir.resolve("lucene").toString()
                )) {
                    ResumeService resumeService = new ResumeService(
                            resumeRepository,
                            resumeChunkRepository,
                            currentUserService,
                            proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
                                case "findByIdForUpdate" -> Optional.of(rows.user());
                                case "toString" -> "AppUserRepositoryMySqlTestDouble";
                                default -> throw new UnsupportedOperationException(
                                        "Unexpected repository call: " + method.getName()
                                );
                            }),
                            null,
                            tempDir.resolve("uploads").toString(),
                            vectorIndex,
                            com.arthur.jdragresume.service.SemanticEmbeddingTestSupport.service(),
                            pendingAwareAnalysisHistoryRepository(connection)
                    );
                    MockMvc mockMvc = MockMvcBuilders
                            .standaloneSetup(new ResumeController(resumeService))
                            .build();

                    mockMvc.perform(delete("/api/resumes/{id}", rows.resumeId()))
                            .andExpect(status().isNoContent());
                }

                assertEquals(0, count(connection, "analysis_history", "resume_id", rows.resumeId()));
                assertEquals(0, count(connection, "resume_chunk", "resume_id", rows.resumeId()));
            } finally {
                cleanup(connection, rows);
            }
        }
    }

    @Test
    void deletingAnalyzedJobReturns204AndCascadesAnalysis() throws Exception {
        DatabaseConfiguration database = migrateDatabase();

        try (Connection connection = DriverManager.getConnection(
                database.url(),
                database.username(),
                database.password()
        )) {
            InsertedRows rows = insertAnalyzedResume(connection);
            try {
                JobDescription jobDescription = new JobDescription();
                jobDescription.setUser(rows.user());
                jobDescription.setTitle("Java engineer");
                jobDescription.setCompanyName("Example Co");
                jobDescription.setDescription("Backend role");
                ReflectionTestUtils.setField(jobDescription, "id", rows.jobId());

                JobDescriptionRepository repository = proxy(
                        JobDescriptionRepository.class,
                        (ignored, method, args) -> switch (method.getName()) {
                            case "findByIdAndUserId" -> Optional.of(jobDescription);
                            case "delete" -> {
                                executeDelete(
                                        connection,
                                        "DELETE FROM job_description WHERE id = ?",
                                        rows.jobId()
                                );
                                yield null;
                            }
                            case "toString" -> "JobDescriptionRepositoryMySqlTestDouble";
                            default -> throw new UnsupportedOperationException(
                                    "Unexpected repository call: " + method.getName()
                            );
                        }
                );
                JobDescriptionService service = new JobDescriptionService(
                        repository,
                        new FixedCurrentUserService(rows.user()),
                        proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
                            case "findByIdForUpdate" -> Optional.of(rows.user());
                            case "toString" -> "AppUserRepositoryMySqlTestDouble";
                            default -> throw new UnsupportedOperationException(
                                    "Unexpected repository call: " + method.getName()
                            );
                        }),
                        com.arthur.jdragresume.service.SemanticEmbeddingTestSupport.service(),
                        pendingAwareAnalysisHistoryRepository(connection),
                        200,
                        java.time.Clock.systemDefaultZone()
                );
                MockMvc mockMvc = MockMvcBuilders
                        .standaloneSetup(new JobDescriptionController(service, null))
                        .build();

                mockMvc.perform(delete("/api/job-descriptions/{id}", rows.jobId()))
                        .andExpect(status().isNoContent());

                assertEquals(0, count(connection, "analysis_history", "job_description_id", rows.jobId()));
                assertEquals(1, count(connection, "resume", "id", rows.resumeId()));
            } finally {
                cleanup(connection, rows);
            }
        }
    }

    /**
     * 删除路径现在会先查关联的 PENDING 分析，所以这里不能塞 null，也不写死 false：
     * 替身直接查同一个连接上的真实数据。夹具插入的是 COMPLETED，因此删除放行；
     * 若将来夹具改成 PENDING，这个替身会如实反映，测试也会如实失败。
     */
    private static AnalysisHistoryRepository pendingAwareAnalysisHistoryRepository(Connection connection) {
        return proxy(
                AnalysisHistoryRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "existsByResume_IdAndStatus" ->
                            countPending(connection, "resume_id", (Long) args[0]) > 0;
                    case "existsByJobDescription_IdAndStatus" ->
                            countPending(connection, "job_description_id", (Long) args[0]) > 0;
                    case "toString" -> "AnalysisHistoryRepositoryMySqlTestDouble";
                    default -> throw new UnsupportedOperationException(
                            "Unexpected repository call: " + method.getName()
                    );
                }
        );
    }

    private DatabaseConfiguration migrateDatabase() {
        String url = MYSQL.getJdbcUrl();
        String username = MYSQL.getUsername();
        String password = MYSQL.getPassword();

        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new DatabaseConfiguration(url, username, password);
    }

    private InsertedRows insertAnalyzedResume(Connection connection) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        long userId = insertAndReturnId(
                connection,
                """
                        INSERT INTO app_user
                            (created_at, updated_at, display_name, email, password_hash, username)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                now, now, "Cascade Test", "cascade-" + suffix + "@example.com", "not-used", "cascade-" + suffix
        );
        AppUser user = new AppUser();
        user.setUsername("cascade-" + suffix);
        ReflectionTestUtils.setField(user, "id", userId);

        long resumeId = insertAndReturnId(
                connection,
                """
                        INSERT INTO resume
                            (created_at, updated_at, candidate_name, title, user_id, raw_text)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                now, now, "Arthur", "Cascade test resume", userId, "Java Spring Boot"
        );
        long jobId = insertAndReturnId(
                connection,
                """
                        INSERT INTO job_description
                            (created_at, updated_at, company_name, description, title, user_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                now, now, "Example Co", "Backend role", "Java engineer", userId
        );
        insertAndReturnId(
                connection,
                """
                        INSERT INTO resume_chunk
                            (created_at, updated_at, chunk_index, content, embedding, source_hash, resume_id, user_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                now, now, 0, "Java Spring Boot", "[]", suffix, resumeId, userId
        );
        long analysisId = insertAndReturnId(
                connection,
                """
                        INSERT INTO analysis_history
                            (created_at, updated_at, match_score, status, summary,
                             job_description_id, resume_id, user_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                now, now, 91.25, "COMPLETED", "analysis complete", jobId, resumeId, userId
        );
        return new InsertedRows(user, resumeId, jobId, analysisId);
    }

    private long insertAndReturnId(Connection connection, String sql, Object... values) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("insert did not return a generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    private long count(Connection connection, String table, String column, long id) throws Exception {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static long countPending(Connection connection, String column, long id) throws Exception {
        String sql = "SELECT COUNT(*) FROM analysis_history WHERE " + column + " = ? AND status = 'PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private void cleanup(Connection connection, InsertedRows rows) throws Exception {
        executeDelete(connection, "DELETE FROM analysis_history WHERE id = ?", rows.analysisId());
        executeDelete(connection, "DELETE FROM resume_chunk WHERE resume_id = ?", rows.resumeId());
        executeDelete(connection, "DELETE FROM resume WHERE id = ?", rows.resumeId());
        executeDelete(connection, "DELETE FROM job_description WHERE id = ?", rows.jobId());
        executeDelete(connection, "DELETE FROM app_user WHERE id = ?", rows.user().getId());
    }

    private static void executeDelete(Connection connection, String sql, long id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class FixedCurrentUserService extends CurrentUserService {
        private final AppUser user;

        private FixedCurrentUserService(AppUser user) {
            super(proxy(AppUserRepository.class, (ignored, method, args) -> {
                throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
            }));
            this.user = user;
        }

        @Override
        public AppUser getCurrentUser() {
            return user;
        }
    }

    private record InsertedRows(AppUser user, long resumeId, long jobId, long analysisId) {
    }

    private record DatabaseConfiguration(String url, String username, String password) {
    }
}
