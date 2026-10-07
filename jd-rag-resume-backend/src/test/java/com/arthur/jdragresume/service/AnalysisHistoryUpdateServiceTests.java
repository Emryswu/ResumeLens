package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AnalysisSubmissionRefund;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AnalysisSubmissionRefundRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHistoryUpdateServiceTests {

    @Test
    void completeIfPendingIgnoresRowsThatAreNoLongerPending() {
        AnalysisHistory failed = new AnalysisHistory();
        ReflectionTestUtils.setField(failed, "id", 9L);
        failed.setStatus(AnalysisStatus.FAILED);
        AnalysisHistoryUpdateService service = serviceReturning(failed);

        boolean completed = service.completeIfPending(9L, history -> history.setSummary("late success"));

        assertFalse(completed);
        assertEquals(AnalysisStatus.FAILED, failed.getStatus());
        assertEquals(null, failed.getSummary());
    }

    @Test
    void completeIfPendingWritesOnlyWhilePending() {
        AnalysisHistory pending = currentPending();
        ReflectionTestUtils.setField(pending, "id", 3L);
        AnalysisHistoryUpdateService service = serviceReturning(pending);

        boolean completed = service.completeIfPending(3L, history -> history.setSummary("done"));

        assertTrue(completed);
        assertEquals(AnalysisStatus.COMPLETED, pending.getStatus());
        assertEquals("done", pending.getSummary());
    }

    @Test
    void completeIfPendingRejectsResultsForChangedInputs() {
        AnalysisHistory pending = currentPending();
        ReflectionTestUtils.setField(pending, "id", 5L);
        pending.getResume().setRawText("changed after submission");
        AnalysisHistoryUpdateService service = serviceReturning(pending);

        boolean completed = service.completeIfPending(5L, history -> history.setSummary("stale result"));

        assertFalse(completed);
        assertEquals(AnalysisStatus.FAILED, pending.getStatus());
        assertEquals("分析期间简历或岗位内容已更新，请基于最新内容重新分析", pending.getSummary());
    }

    @Test
    void failIfPendingDoesNotOverwriteCompletedRows() {
        AnalysisHistory completed = new AnalysisHistory();
        ReflectionTestUtils.setField(completed, "id", 4L);
        completed.setStatus(AnalysisStatus.COMPLETED);
        completed.setSummary("ok");
        AnalysisHistoryUpdateService service = serviceReturning(completed);

        assertFalse(service.failIfPending(4L, "AI analysis failed: Timeout"));
        assertEquals(AnalysisStatus.COMPLETED, completed.getStatus());
        assertEquals("ok", completed.getSummary());
    }

    private static AnalysisHistory currentPending() {
        Resume resume = new Resume();
        resume.setTitle("Backend resume");
        resume.setRawText("Java Spring Boot");
        JobDescription job = new JobDescription();
        job.setTitle("Java engineer");
        job.setDescription("Build backend services");

        AnalysisHistory pending = new AnalysisHistory();
        pending.setResume(resume);
        pending.setJobDescription(job);
        pending.setResumeFingerprint(ContentFingerprints.resume(resume));
        pending.setJobFingerprint(ContentFingerprints.job(job));
        pending.setStatus(AnalysisStatus.PENDING);
        return pending;
    }

    @Test
    void failIfPendingAndRefundGivesTheSubmissionBackOnce() {
        AnalysisHistory pending = currentPending();
        ReflectionTestUtils.setField(pending, "id", 6L);
        pending.setSubmissionLogId(41L);
        List<AnalysisSubmissionRefund> refunds = new ArrayList<>();
        AnalysisHistoryUpdateService service = serviceReturning(pending, refunds);

        assertTrue(service.failIfPendingAndRefund(6L, "分析结果校验未通过，请稍后重试", "AI_RESPONSE_CITATION_INVALID"));

        assertEquals(AnalysisStatus.FAILED, pending.getStatus());
        assertEquals("分析结果校验未通过，请稍后重试", pending.getSummary());
        assertEquals(1, refunds.size());
        assertEquals(41L, refunds.getFirst().getSubmissionLogId());
        assertEquals("AI_RESPONSE_CITATION_INVALID", refunds.getFirst().getReason());
        // A second failure report for the same analysis is a no-op: the row is no longer pending.
        assertFalse(service.failIfPendingAndRefund(6L, "again", "AI_RESPONSE_PARSE_FAILED"));
        assertEquals(1, refunds.size());
    }

    @Test
    void failIfPendingAndRefundSkipsRowsWithoutASubmissionOrAlreadyRefunded() {
        AnalysisHistory legacy = currentPending();
        ReflectionTestUtils.setField(legacy, "id", 7L);
        List<AnalysisSubmissionRefund> refunds = new ArrayList<>();

        assertTrue(serviceReturning(legacy, refunds).failIfPendingAndRefund(7L, "failed", "AI_RESPONSE_PARSE_FAILED"));
        assertEquals(AnalysisStatus.FAILED, legacy.getStatus());
        assertTrue(refunds.isEmpty());

        AnalysisHistory refundedBefore = currentPending();
        ReflectionTestUtils.setField(refundedBefore, "id", 8L);
        refundedBefore.setSubmissionLogId(41L);
        AnalysisSubmissionRefund existing = new AnalysisSubmissionRefund();
        existing.setSubmissionLogId(41L);
        refunds.add(existing);

        assertTrue(serviceReturning(refundedBefore, refunds).failIfPendingAndRefund(8L, "failed", "AI_RESPONSE_PARSE_FAILED"));
        assertEquals(1, refunds.size());
    }

    @Test
    void plainFailIfPendingNeverRefunds() {
        AnalysisHistory pending = currentPending();
        ReflectionTestUtils.setField(pending, "id", 10L);
        pending.setSubmissionLogId(42L);
        List<AnalysisSubmissionRefund> refunds = new ArrayList<>();

        assertTrue(serviceReturning(pending, refunds).failIfPending(10L, "AI 分析失败，请稍后重试"));
        assertTrue(refunds.isEmpty());
    }

    private static AnalysisHistoryUpdateService serviceReturning(AnalysisHistory history) {
        return serviceReturning(history, new ArrayList<>());
    }

    private static AnalysisHistoryUpdateService serviceReturning(
            AnalysisHistory history,
            List<AnalysisSubmissionRefund> refunds
    ) {
        AtomicReference<AnalysisHistory> current = new AtomicReference<>(history);
        AnalysisHistoryRepository repository = proxy(AnalysisHistoryRepository.class, (ignored, method, args) -> {
            if ("findByIdForUpdate".equals(method.getName())) {
                return Optional.ofNullable(current.get());
            }
            if ("toString".equals(method.getName())) {
                return "AnalysisHistoryRepositoryTestDouble";
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return new AnalysisHistoryUpdateService(repository, refundRepository(refunds));
    }

    static AnalysisSubmissionRefundRepository refundRepository(List<AnalysisSubmissionRefund> refunds) {
        return proxy(AnalysisSubmissionRefundRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "existsBySubmissionLogId" -> refunds.stream()
                    .anyMatch(refund -> refund.getSubmissionLogId().equals(args[0]));
            case "save" -> {
                refunds.add((AnalysisSubmissionRefund) args[0]);
                yield args[0];
            }
            case "toString" -> "AnalysisSubmissionRefundRepositoryTestDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
