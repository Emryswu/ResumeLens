package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobSemanticMatchServiceTests {
    @Test
    void rankReadsCurrentVectorsWithoutRepositoryWrites() {
        Fixture fixture = fixture(null, null);

        var result = fixture.service.rank(11L, 1);

        assertEquals(1, result.size());
        assertEquals(22L, result.getFirst().job().id());
        assertEquals(0.87, result.getFirst().similarity(), 0.000001);
        assertNull(fixture.embeddings.refreshedResume);
        assertEquals(List.of(), fixture.embeddings.refreshedJobs);
        assertNull(fixture.savedResume.get());
        assertNull(fixture.savedJobs.get());
    }

    @Test
    void rankCanBeLimitedToGivenJobsAndIgnoresStaleVectorsOutsideThem() {
        // 21 is stale, but only 22 is asked for: the collected batch ranks without a refresh.
        Fixture fixture = fixture(null, 21L);

        var result = fixture.service.rank(11L, 5, List.of(22L, 999L));

        assertEquals(List.of(22L), result.stream().map(match -> match.job().id()).toList());
    }

    @Test
    void rankReportsStaleVectorsWithoutRefreshingOrSavingThem() {
        Fixture fixture = fixture(null, 21L);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> fixture.service.rank(11L, 200)
        );

        assertEquals("SEMANTIC_EMBEDDING_STALE", error.getCode());
        assertNull(fixture.embeddings.refreshedResume);
        assertEquals(List.of(), fixture.embeddings.refreshedJobs);
        assertNull(fixture.savedResume.get());
        assertNull(fixture.savedJobs.get());
    }

    @Test
    void explicitRefreshUpdatesOnlyStaleVectors() {
        Fixture fixture = fixture(11L, 21L);

        fixture.service.refreshStaleEmbeddings(11L);

        assertSame(fixture.resume, fixture.embeddings.refreshedResume);
        assertSame(fixture.resume, fixture.savedResume.get());
        assertEquals(List.of(fixture.lower), fixture.embeddings.refreshedJobs);
        assertEquals(List.of(fixture.lower), fixture.savedJobs.get());
    }

    private static Fixture fixture(Long staleResumeId, Long staleJobId) {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 7L);
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("Java resume");
        resume.setRawText("Java Spring Boot MySQL");
        ReflectionTestUtils.setField(resume, "id", 11L);
        JobDescription lower = job(user, 21L, "Backend", "Java");
        JobDescription higher = job(user, 22L, "RAG Backend", "Java RAG");
        AtomicReference<Resume> savedResume = new AtomicReference<>();
        AtomicReference<List<JobDescription>> savedJobs = new AtomicReference<>();

        ResumeRepository resumes = proxy(ResumeRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdAndUserId" -> Optional.of(resume);
            case "save" -> {
                savedResume.set((Resume) args[0]);
                yield args[0];
            }
            case "toString" -> "ResumeRepositorySemanticMatchDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        JobDescriptionRepository jobs = proxy(
                JobDescriptionRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "findAllByUserId" -> List.of(lower, higher);
                    case "saveAll" -> {
                        @SuppressWarnings("unchecked")
                        List<JobDescription> values = (List<JobDescription>) args[0];
                        savedJobs.set(values);
                        yield values;
                    }
                    case "toString" -> "JobRepositorySemanticMatchDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        TrackingEmbeddings embeddings = new TrackingEmbeddings(staleResumeId, staleJobId);
        JobSemanticMatchService service = new JobSemanticMatchService(
                jobs,
                resumes,
                fixedCurrentUser(user),
                embeddings
        );
        return new Fixture(service, embeddings, resume, lower, savedResume, savedJobs);
    }

    private static JobDescription job(AppUser user, Long id, String title, String description) {
        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle(title);
        job.setCompanyName("Example");
        job.setDescription(description);
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    private static CurrentUserService fixedCurrentUser(AppUser user) {
        return new CurrentUserService(proxy(AppUserRepository.class, (ignored, method, args) -> null)) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class TrackingEmbeddings extends SemanticEmbeddingService {
        private final Long staleResumeId;
        private final Long staleJobId;
        private Resume refreshedResume;
        private List<JobDescription> refreshedJobs = List.of();

        private TrackingEmbeddings(Long staleResumeId, Long staleJobId) {
            super(
                    SemanticEmbeddingTestSupport.model(text -> new float[]{1.0f, 0.0f, 0.0f}),
                    SemanticEmbeddingTestSupport.properties(3)
            );
            this.staleResumeId = staleResumeId;
            this.staleJobId = staleJobId;
        }

        @Override
        public boolean isCurrent(Resume resume) {
            return !resume.getId().equals(staleResumeId);
        }

        @Override
        public boolean isCurrent(JobDescription job) {
            return !job.getId().equals(staleJobId);
        }

        @Override
        public void refresh(Resume resume) {
            refreshedResume = resume;
        }

        @Override
        public void refreshJobs(List<JobDescription> jobs) {
            refreshedJobs = List.copyOf(jobs);
        }

        @Override
        public double similarity(Resume resume, JobDescription job) {
            return job.getId() == 22L ? 0.87 : 0.61;
        }
    }

    private record Fixture(
            JobSemanticMatchService service,
            TrackingEmbeddings embeddings,
            Resume resume,
            JobDescription lower,
            AtomicReference<Resume> savedResume,
            AtomicReference<List<JobDescription>> savedJobs
    ) {
    }
}
