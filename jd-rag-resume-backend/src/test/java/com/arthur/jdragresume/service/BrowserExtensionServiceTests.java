package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.dto.browser.BrowserExtensionAnalyzeRequest;
import com.arthur.jdragresume.dto.browser.BrowserExtensionAnalyzeResponse;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.entity.AnalysisStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserExtensionServiceTests {
    private FixedJobDescriptionService jobService;
    private FixedAnalysisHistoryService historyService;
    private FixedAiAnalysisService aiService;
    private BrowserExtensionService service;

    @BeforeEach
    void setUp() {
        jobService = new FixedJobDescriptionService();
        historyService = new FixedAnalysisHistoryService();
        aiService = new FixedAiAnalysisService();
        service = new BrowserExtensionService(jobService, historyService, aiService);
    }

    @Test
    void reusesACompletedAnalysisForAnUnchangedDuplicate() {
        jobService.result = new JobCaptureResponse(job(), true, false);
        historyService.result = Optional.of(analysis(AnalysisStatus.COMPLETED, 88L));

        BrowserExtensionAnalyzeResponse response = service.analyze(request(false));

        assertTrue(response.reusedAnalysis());
        assertEquals(0, aiService.calls);
    }

    @Test
    void reconnectsToAnExistingPendingAnalysis() {
        jobService.result = new JobCaptureResponse(job(), true, false);
        historyService.result = Optional.of(analysis(AnalysisStatus.PENDING, 88L));

        BrowserExtensionAnalyzeResponse response = service.analyze(request(false));

        assertTrue(response.reusedAnalysis());
        assertEquals(0, aiService.calls);
    }

    @Test
    void submitsANewAnalysisWhenTheCapturedJdChanged() {
        jobService.result = new JobCaptureResponse(job(), true, true);
        historyService.result = Optional.of(analysis(AnalysisStatus.COMPLETED, 88L));
        aiService.result = analysis(AnalysisStatus.PENDING, 89L);

        BrowserExtensionAnalyzeResponse response = service.analyze(request(false));

        assertFalse(response.reusedAnalysis());
        assertEquals(1, aiService.calls);
        assertEquals(new AiAnalysisRequest(7L, 11L), aiService.lastRequest);
    }

    @Test
    void forceReanalyzeBypassesACompletedCachedResult() {
        jobService.result = new JobCaptureResponse(job(), true, false);
        historyService.result = Optional.of(analysis(AnalysisStatus.COMPLETED, 88L));
        aiService.result = analysis(AnalysisStatus.PENDING, 89L);

        BrowserExtensionAnalyzeResponse response = service.analyze(request(true));

        assertFalse(response.reusedAnalysis());
        assertEquals(1, aiService.calls);
        assertEquals(89L, response.analysis().id());
    }

    private BrowserExtensionAnalyzeRequest request(boolean force) {
        return new BrowserExtensionAnalyzeRequest(
                7L,
                new JobCaptureRequest(
                        "Java 后端",
                        "示例科技",
                        "杭州",
                        "全职",
                        "Spring Boot",
                        "Java",
                        "BOSS",
                        "https://www.zhipin.com/job_detail/key.html",
                        "key"
                ),
                force
        );
    }

    private JobDescriptionResponse job() {
        LocalDateTime now = LocalDateTime.now();
        return new JobDescriptionResponse(
                11L, 1L, "Java 后端", "示例科技", "杭州", "全职", null,
                "Spring Boot", "Java", "BOSS",
                "https://www.zhipin.com/job_detail/key.html", "key", "fingerprint", now, "FULL", now, now
        );
    }

    private AnalysisHistoryResponse analysis(AnalysisStatus status, Long id) {
        LocalDateTime now = LocalDateTime.now();
        return new AnalysisHistoryResponse(
                id, 1L, "arthur", 7L, "Java 简历", 11L, "Java 后端",
                status == AnalysisStatus.COMPLETED ? new BigDecimal("86.00") : null,
                status, "summary", null, null, null, null, null, null, null, now, now
        );
    }

    private static final class FixedJobDescriptionService extends JobDescriptionService {
        private JobCaptureResponse result;

        // 只覆盖 capture()，delete() 不会被调用，所以新增的
        // AnalysisHistoryRepository 依赖传 null 即可。
        private FixedJobDescriptionService() {
            super(null, null, null, SemanticEmbeddingTestSupport.service(), null, 200);
        }

        @Override
        public JobCaptureResponse capture(JobCaptureRequest request) {
            return result;
        }
    }

    private static final class FixedAnalysisHistoryService extends AnalysisHistoryService {
        private Optional<AnalysisHistoryResponse> result = Optional.empty();

        private FixedAnalysisHistoryService() {
            super(null, null, null, null);
        }

        @Override
        public Optional<AnalysisHistoryResponse> findLatest(Long resumeId, Long jobDescriptionId) {
            return result;
        }
    }

    private static final class FixedAiAnalysisService extends AiAnalysisService {
        private int calls;
        private AiAnalysisRequest lastRequest;
        private AnalysisHistoryResponse result;

        private FixedAiAnalysisService() {
            super(null, null, null, null, null);
        }

        @Override
        public Submission submit(AiAnalysisRequest request) {
            calls += 1;
            lastRequest = request;
            return new Submission(result, false);
        }
    }
}
