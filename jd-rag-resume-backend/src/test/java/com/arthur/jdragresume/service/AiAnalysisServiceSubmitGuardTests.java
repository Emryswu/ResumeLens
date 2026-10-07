package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAnalysisServiceSubmitGuardTests {
    private AppUser user;
    private Resume resume;
    private JobDescription job;
    private RecordingGuard guard;
    private RecordingExecutor executor;
    private AiAnalysisService service;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        resume = new Resume();
        resume.setTitle("Backend resume");
        resume.setRawText("Java Spring Boot");
        ReflectionTestUtils.setField(resume, "id", 2L);
        job = new JobDescription();
        job.setTitle("Java backend engineer");
        ReflectionTestUtils.setField(job, "id", 3L);

        CurrentUserService currentUserService = new FixedCurrentUserService(user);
        guard = new RecordingGuard();
        executor = new RecordingExecutor();
        service = new AiAnalysisService(
                currentUserService,
                null,
                guard,
                null,
                executor
        );
    }

    @Test
    void analyzeAdmitsThroughSubmitGuardBeforeQueueing() {
        guard.result = pendingHistory();

        AnalysisHistoryResponse response = service.analyze(new AiAnalysisRequest(2L, 3L));

        assertEquals(1, guard.admits.get());
        assertEquals(user, guard.admittedUser);
        assertEquals(2L, guard.admittedResumeId);
        assertEquals(3L, guard.admittedJobId);
        assertEquals(1, executor.calls.get());
        assertEquals(9L, response.id());
        assertEquals(AnalysisStatus.PENDING, response.status());
    }

    @Test
    void analyzePropagatesGuardRejectionAndDoesNotQueue() {
        guard.failure = new BusinessException("ANALYSIS_RATE_LIMITED", "too many analysis requests");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.analyze(new AiAnalysisRequest(2L, 3L))
        );

        assertEquals("ANALYSIS_RATE_LIMITED", exception.getCode());
        assertEquals(1, guard.admits.get());
        assertEquals(0, executor.calls.get());
    }

    @Test
    void reusedPendingAdmissionDoesNotQueueAnotherRun() {
        guard.result = pendingHistory();
        guard.reusedPending = true;

        AnalysisHistoryResponse response = service.analyze(new AiAnalysisRequest(2L, 3L));

        assertEquals(1, guard.admits.get());
        assertEquals(0, executor.calls.get());
        assertEquals(9L, response.id());
        assertEquals(AnalysisStatus.PENDING, response.status());
    }

    private AnalysisHistory pendingHistory() {
        AnalysisHistory history = new AnalysisHistory();
        ReflectionTestUtils.setField(history, "id", 9L);
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(job);
        history.setStatus(AnalysisStatus.PENDING);
        history.setSummary("AI analysis is pending");
        return history;
    }

    private static final class RecordingGuard extends AnalysisSubmitGuard {
        private final AtomicInteger admits = new AtomicInteger();
        private AppUser admittedUser;
        private Long admittedResumeId;
        private Long admittedJobId;
        private AnalysisHistory result;
        private boolean reusedPending;
        private BusinessException failure;

        // admit() 已被完全覆盖，父类依赖一律不使用；第二、三个参数为
        // AnalysisSubmissionLogRepository 与 AnalysisSubmissionRefundRepository。
        private RecordingGuard() {
            super(null, null, null, null, null, null, 2, 10, 10);
        }

        @Override
        public Admission admit(AppUser user, Long resumeId, Long jobDescriptionId) {
            admits.incrementAndGet();
            admittedUser = user;
            admittedResumeId = resumeId;
            admittedJobId = jobDescriptionId;
            if (failure != null) {
                throw failure;
            }
            return new Admission(result, reusedPending);
        }
    }

    private static final class RecordingExecutor implements TaskExecutor {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void execute(Runnable task) {
            calls.incrementAndGet();
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
                throw new UnsupportedOperationException(method.getName());
            }));
            this.user = user;
        }

        @Override
        public AppUser getCurrentUser() {
            return user;
        }
    }
}
