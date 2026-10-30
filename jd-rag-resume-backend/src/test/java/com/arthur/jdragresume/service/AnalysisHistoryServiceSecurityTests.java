package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AnalysisHistorySummaryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnalysisHistoryServiceSecurityTests {
    private RepositoryState repositoryState;
    private AnalysisHistoryService analysisHistoryService;
    private AppUser user;
    private Resume resume;
    private JobDescription jobDescription;

    @BeforeEach
    void setUp() {
        repositoryState = new RepositoryState();
        AnalysisHistoryRepository analysisHistoryRepository = proxy(
                AnalysisHistoryRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "findFirstByUser_IdAndResume_IdAndJobDescription_IdAndResumeFingerprintAndJobFingerprintOrderByIdDesc" -> {
                        repositoryState.latestOneArguments = args.clone();
                        yield repositoryState.latestOne;
                    }
                    case "findLatestCurrentForEachJobByUserIdAndResumeId" -> {
                        repositoryState.latestArguments = args.clone();
                        yield repositoryState.latest;
                    }
                    case "toString" -> "AnalysisHistoryRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(
                            "Unexpected repository call: " + method.getName()
                    );
                }
        );

        user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        resume = new Resume();
        resume.setTitle("Backend resume");
        resume.setRawText("Java Spring Boot");
        ReflectionTestUtils.setField(resume, "id", 2L);
        jobDescription = new JobDescription();
        jobDescription.setTitle("Java backend engineer");
        jobDescription.setDescription("Build backend services");
        ReflectionTestUtils.setField(jobDescription, "id", 3L);

        CurrentUserService currentUserService = new FixedCurrentUserService(user);
        analysisHistoryService = new AnalysisHistoryService(
                analysisHistoryRepository,
                currentUserService,
                new FixedResumeService(currentUserService, resume),
                new FixedJobDescriptionService(currentUserService, jobDescription)
        );
    }

    @Test
    void latestForEachJobUsesCurrentUserAndSelectedResume() {
        repositoryState.latest = List.of(new AnalysisHistorySummaryResponse(
                11L,
                2L,
                3L,
                new BigDecimal("88.00"),
                AnalysisStatus.COMPLETED,
                LocalDateTime.now()
        ));

        var result = analysisHistoryService.findLatestForEachJob(2L);

        assertEquals(1, result.size());
        assertEquals(11L, result.getFirst().id());
        assertEquals(3L, result.getFirst().jobDescriptionId());
        assertEquals(new BigDecimal("88.00"), result.getFirst().matchScore());
        assertEquals(1L, repositoryState.latestArguments[0]);
        assertEquals(2L, repositoryState.latestArguments[1]);
        assertEquals(ContentFingerprints.resume(resume), repositoryState.latestArguments[2]);
    }

    @Test
    void latestAnalysisIsLookedUpWithCurrentOwnedInputFingerprints() {
        AnalysisHistory history = new AnalysisHistory();
        ReflectionTestUtils.setField(history, "id", 12L);
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(jobDescription);
        history.setStatus(AnalysisStatus.COMPLETED);
        repositoryState.latestOne = Optional.of(history);

        var result = analysisHistoryService.findLatest(2L, 3L);

        assertEquals(12L, result.orElseThrow().id());
        assertEquals(1L, repositoryState.latestOneArguments[0]);
        assertEquals(2L, repositoryState.latestOneArguments[1]);
        assertEquals(3L, repositoryState.latestOneArguments[2]);
        assertEquals(ContentFingerprints.resume(resume), repositoryState.latestOneArguments[3]);
        assertEquals(ContentFingerprints.job(jobDescription), repositoryState.latestOneArguments[4]);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RepositoryState {
        private Optional<AnalysisHistory> latestOne = Optional.empty();
        private Object[] latestOneArguments;
        private List<AnalysisHistorySummaryResponse> latest = List.of();
        private Object[] latestArguments;
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

    private static final class FixedResumeService extends ResumeService {
        private final Resume resume;

        // 这里只用到 getEntityForCurrentUser()，delete() 永不触发，
        // 因此新增的 AnalysisHistoryRepository 依赖传 null 即可。
        private FixedResumeService(CurrentUserService currentUserService, Resume resume) {
            super(
                    proxy(ResumeRepository.class, (ignored, method, args) -> null),
                    proxy(ResumeChunkRepository.class, (ignored, method, args) -> null),
                    currentUserService,
                    proxy(AppUserRepository.class, (ignored, method, args) -> null),
                    null,
                    "unused",
                    null,
                    SemanticEmbeddingTestSupport.service(),
                    null
            );
            this.resume = resume;
        }

        @Override
        public Resume getEntityForCurrentUser(Long id) {
            return resume;
        }
    }

    private static final class FixedJobDescriptionService extends JobDescriptionService {
        private final JobDescription jobDescription;

        private FixedJobDescriptionService(
                CurrentUserService currentUserService,
                JobDescription jobDescription
        ) {
            super(
                    proxy(JobDescriptionRepository.class, (ignored, method, args) -> null),
                    currentUserService,
                    proxy(AppUserRepository.class, (ignored, method, args) -> null),
                    SemanticEmbeddingTestSupport.service(),
                    null,
                    200,
                    java.time.Clock.systemDefaultZone()
            );
            this.jobDescription = jobDescription;
        }

        @Override
        public JobDescription getEntityForCurrentUser(Long id) {
            return jobDescription;
        }
    }
}
