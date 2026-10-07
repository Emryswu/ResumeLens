package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AnalysisSubmissionLog;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AnalysisSubmissionLogRepository;
import com.arthur.jdragresume.repository.AnalysisSubmissionRefundRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisSubmitGuardTests {
    private RepositoryState state;
    private AnalysisSubmitGuard guard;
    private AppUser user;
    private Resume resume;
    private JobDescription job;

    @BeforeEach
    void setUp() {
        state = new RepositoryState();
        user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        resume = new Resume();
        resume.setTitle("resume");
        resume.setRawText("Java Spring Boot");
        ReflectionTestUtils.setField(resume, "id", 2L);
        job = new JobDescription();
        job.setTitle("job");
        job.setDescription("Build backend services");
        job.setRequirements("Java");
        ReflectionTestUtils.setField(job, "id", 3L);
        state.user = user;

        AnalysisHistoryRepository historyRepository = proxy(AnalysisHistoryRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findFirstByUser_IdAndResume_IdAndJobDescription_IdAndStatusAndResumeFingerprintAndJobFingerprintOrderByIdDesc" ->
                            Optional.ofNullable(state.existingPending);
                    case "countByUser_IdAndStatus" -> state.pendingCount;
                    case "save" -> {
                        state.saved = (AnalysisHistory) args[0];
                        ReflectionTestUtils.setField(state.saved, "id", 9L);
                        yield state.saved;
                    }
                    case "toString" -> "AnalysisHistoryRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        // 限流计数已从 analysis_history 迁到只追加的 analysis_submission_log，
        // 测试替身必须跟着搬：若仍在历史仓库上模拟计数，用例即使能编译
        // 也不再覆盖真实的限流路径。
        AnalysisSubmissionLogRepository submissionLogRepository =
                proxy(AnalysisSubmissionLogRepository.class, (ignored, method, args) ->
                        switch (method.getName()) {
                            case "countByUser_IdAndCreatedAtAfter" -> state.submittedCount;
                            case "save" -> {
                                state.savedSubmissionLog = (AnalysisSubmissionLog) args[0];
                                ReflectionTestUtils.setField(state.savedSubmissionLog, "id", 7L);
                                yield state.savedSubmissionLog;
                            }
                            case "toString" -> "AnalysisSubmissionLogRepositoryTestDouble";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        AnalysisSubmissionRefundRepository refundRepository =
                proxy(AnalysisSubmissionRefundRepository.class, (ignored, method, args) ->
                        switch (method.getName()) {
                            case "countRefundedSubmissions" -> state.refundedCount;
                            case "toString" -> "AnalysisSubmissionRefundRepositoryTestDouble";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        AppUserRepository userRepository = proxy(AppUserRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(state.user);
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        ResumeRepository resumeRepository = proxy(ResumeRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdAndUserId" -> Optional.of(resume);
                    case "toString" -> "ResumeRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        JobDescriptionRepository jobDescriptionRepository = proxy(JobDescriptionRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdAndUserId" -> Optional.of(job);
                    case "toString" -> "JobDescriptionRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        guard = new AnalysisSubmitGuard(
                historyRepository,
                submissionLogRepository,
                refundRepository,
                userRepository,
                resumeRepository,
                jobDescriptionRepository,
                2,
                10,
                10
        );
    }

    @Test
    void admitsFirstPendingAnalysis() {
        AnalysisSubmitGuard.Admission admission = guard.admit(user, resume.getId(), job.getId());
        AnalysisHistory saved = admission.history();
        assertFalse(admission.reusedPending());
        assertEquals(AnalysisStatus.PENDING, saved.getStatus());
        assertEquals(user, saved.getUser());
        assertEquals(9L, saved.getId());
        assertEquals(ContentFingerprints.resume(resume), saved.getResumeFingerprint());
        assertEquals(ContentFingerprints.job(job), saved.getJobFingerprint());
    }

    @Test
    void recordsSubmissionInTheAppendOnlyLog() {
        guard.admit(user, resume.getId(), job.getId());

        assertNotNull(state.savedSubmissionLog);
        assertSame(user, state.savedSubmissionLog.getUser());
    }

    @Test
    void reusesPendingAnalysisForTheSameInputVersion() {
        state.existingPending = new AnalysisHistory();
        ReflectionTestUtils.setField(state.existingPending, "id", 8L);

        AnalysisSubmitGuard.Admission admission = guard.admit(user, resume.getId(), job.getId());

        assertTrue(admission.reusedPending());
        assertSame(state.existingPending, admission.history());
        // 复用已有 PENDING 不算一次新提交，不应计入限流。
        assertNull(state.savedSubmissionLog);
    }

    @Test
    void rejectsWhenUserAlreadyHasMaxPending() {
        state.pendingCount = 2L;
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> guard.admit(user, resume.getId(), job.getId())
        );
        assertEquals("ANALYSIS_TOO_MANY_PENDING", exception.getCode());
    }

    @Test
    void rejectsWhenSubmitWindowIsExhausted() {
        state.submittedCount = 10L;
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> guard.admit(user, resume.getId(), job.getId())
        );
        assertEquals("ANALYSIS_RATE_LIMITED", exception.getCode());
        assertNull(state.saved);
        assertNull(state.savedSubmissionLog);
    }

    @Test
    void linksTheNewAnalysisToItsSubmissionSoItCanBeRefunded() {
        AnalysisHistory saved = guard.admit(user, resume.getId(), job.getId()).history();

        assertEquals(7L, saved.getSubmissionLogId());
    }

    @Test
    void refundedSubmissionsDoNotCountTowardsTheWindow() {
        state.submittedCount = 10L;
        state.refundedCount = 1L;

        AnalysisSubmitGuard.Admission admission = guard.admit(user, resume.getId(), job.getId());

        assertFalse(admission.reusedPending());
        assertNotNull(state.savedSubmissionLog);
    }

    @Test
    void refundsCannotLiftTheHardCap() {
        // Every one of them refunded, e.g. a resume crafted to make the model fail on purpose.
        state.submittedCount = 20L;
        state.refundedCount = 20L;

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> guard.admit(user, resume.getId(), job.getId())
        );

        assertEquals("ANALYSIS_RATE_LIMITED", exception.getCode());
        assertNull(state.savedSubmissionLog);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RepositoryState {
        private AppUser user;
        private AnalysisHistory existingPending;
        private long pendingCount;
        private long submittedCount;
        private long refundedCount;
        private AnalysisHistory saved;
        private AnalysisSubmissionLog savedSubmissionLog;
    }
}
