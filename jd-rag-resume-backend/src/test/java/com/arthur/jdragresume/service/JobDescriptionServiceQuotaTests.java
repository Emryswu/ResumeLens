package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobDescriptionServiceQuotaTests {
    private static final int MAX_PER_USER = 200;

    private RepositoryState state;
    private JobDescriptionService service;
    private AppUser user;

    @BeforeEach
    void setUp() {
        state = new RepositoryState();
        user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);

        JobDescriptionRepository jobDescriptionRepository =
                proxy(JobDescriptionRepository.class, (ignored, method, args) -> {
                    state.calls.add(method.getName());
                    return switch (method.getName()) {
                        case "countByUserId" -> state.existingCount;
                        case "save" -> {
                            JobDescription saved = (JobDescription) args[0];
                            ReflectionTestUtils.setField(saved, "id", ++state.savedCount);
                            yield saved;
                        }
                        case "toString" -> "JobDescriptionRepositoryTestDouble";
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        AppUserRepository appUserRepository = proxy(AppUserRepository.class, (ignored, method, args) -> {
            state.calls.add(method.getName());
            return switch (method.getName()) {
                case "findByIdForUpdate" -> Optional.of(user);
                case "toString" -> "AppUserRepositoryTestDouble";
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });

        service = new JobDescriptionService(
                jobDescriptionRepository,
                currentUser(user),
                appUserRepository,
                SemanticEmbeddingTestSupport.service(),
                null,
                MAX_PER_USER,
                java.time.Clock.systemDefaultZone()
        );
    }

    @Test
    void allowsCreateBelowQuota() {
        state.existingCount = MAX_PER_USER - 1L;
        assertEquals("Backend Engineer", service.create(request("Backend Engineer")).title());
    }

    @Test
    void rejectsCreateAtQuota() {
        state.existingCount = MAX_PER_USER;
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(request("Backend Engineer"))
        );
        assertEquals("JOB_DESCRIPTION_QUOTA_EXCEEDED", exception.getCode());
        assertEquals(0, state.savedCount);
    }

    @Test
    void rejectsBulkImportThatWouldOverrunQuotaInsteadOfSavingPartially() {
        state.existingCount = MAX_PER_USER - 5L;
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.bulkImport(bulkRequest(10))
        );
        assertEquals("JOB_DESCRIPTION_QUOTA_EXCEEDED", exception.getCode());
        // 整批一起判定：不能先存前 5 条再报错
        assertEquals(0, state.savedCount);
    }

    @Test
    void allowsBulkImportThatExactlyFillsQuota() {
        state.existingCount = MAX_PER_USER - 10L;
        assertEquals(10, service.bulkImport(bulkRequest(10)).size());
        assertEquals(10, state.savedCount);
    }

    @Test
    void locksUserRowBeforeCounting() {
        state.existingCount = 0L;
        service.bulkImport(bulkRequest(1));
        // 先锁用户行再计数，否则两个并发 /import 会各自读到旧计数、双双通过校验
        assertTrue(
                state.calls.indexOf("findByIdForUpdate") < state.calls.indexOf("countByUserId"),
                "expected findByIdForUpdate before countByUserId, got " + state.calls
        );
    }

    @Test
    void rejectsDeleteWhileAnAnalysisUsingTheJobIsStillPending() {
        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle("Java backend engineer");
        job.setCompanyName("Acme");
        job.setDescription("description");
        ReflectionTestUtils.setField(job, "id", 4L);

        AtomicInteger deletes = new AtomicInteger();
        JobDescriptionRepository jobDescriptionRepository =
                proxy(JobDescriptionRepository.class, (ignored, method, args) ->
                        switch (method.getName()) {
                            case "findByIdAndUserId" -> Optional.of(job);
                            case "delete" -> {
                                deletes.incrementAndGet();
                                yield null;
                            }
                            case "toString" -> "JobDescriptionRepositoryTestDouble";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        AppUserRepository appUserRepository = proxy(AppUserRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(user);
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AnalysisHistoryRepository analysisHistoryRepository =
                proxy(AnalysisHistoryRepository.class, (ignored, method, args) ->
                        switch (method.getName()) {
                            case "existsByJobDescription_IdAndStatus" -> true;
                            case "toString" -> "AnalysisHistoryRepositoryTestDouble";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        JobDescriptionService deleteService = new JobDescriptionService(
                jobDescriptionRepository,
                currentUser(user),
                appUserRepository,
                SemanticEmbeddingTestSupport.service(),
                analysisHistoryRepository,
                MAX_PER_USER,
                java.time.Clock.systemDefaultZone()
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> deleteService.delete(4L));

        assertEquals("ANALYSIS_DELETE_PENDING", exception.getCode());
        // 级联删除会连带删掉 PENDING 历史，把“进行中”计数凭空清掉，所以这里必须一行都不删
        assertEquals(0, deletes.get());
    }

    private static JobDescriptionRequest request(String title) {
        return new JobDescriptionRequest(title, "Acme", "Remote", "FULL_TIME", "description", "requirements");
    }

    private static JobDescriptionBulkImportRequest bulkRequest(int count) {
        return new JobDescriptionBulkImportRequest(
                IntStream.range(0, count).mapToObj(index -> request("Job " + index)).toList()
        );
    }

    private static CurrentUserService currentUser(AppUser user) {
        return new CurrentUserService(
                proxy(AppUserRepository.class, (ignored, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                })
        ) {
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

    private static final class RepositoryState {
        private final List<String> calls = new ArrayList<>();
        private long existingCount;
        private long savedCount;
    }
}
