package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    private final CurrentUserService currentUserService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final AnalysisSubmitGuard analysisSubmitGuard;
    private final AnalysisHistoryUpdateService historyUpdateService;
    private final AiAnalysisWorker aiAnalysisWorker;
    private final TaskExecutor analysisTaskExecutor;

    public AiAnalysisService(
            CurrentUserService currentUserService,
            AnalysisHistoryRepository analysisHistoryRepository,
            AnalysisSubmitGuard analysisSubmitGuard,
            AnalysisHistoryUpdateService historyUpdateService,
            AiAnalysisWorker aiAnalysisWorker,
            @Qualifier("analysisTaskExecutor") TaskExecutor analysisTaskExecutor
    ) {
        this.currentUserService = currentUserService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.analysisSubmitGuard = analysisSubmitGuard;
        this.historyUpdateService = historyUpdateService;
        this.aiAnalysisWorker = aiAnalysisWorker;
        this.analysisTaskExecutor = analysisTaskExecutor;
    }

    public AnalysisHistoryResponse analyze(AiAnalysisRequest request) {
        return submit(request).analysis();
    }

    public Submission submit(AiAnalysisRequest request) {
        AppUser user = currentUserService.getCurrentUser();
        AnalysisSubmitGuard.Admission admission = analysisSubmitGuard.admit(
                user,
                request.resumeId(),
                request.jobDescriptionId()
        );
        AnalysisHistory saved = admission.history();
        if (admission.reusedPending()) {
            return new Submission(AnalysisHistoryResponse.from(saved), true);
        }
        try {
            analysisTaskExecutor.execute(() -> aiAnalysisWorker.process(saved.getId()));
        } catch (RuntimeException ex) {
            historyUpdateService.failIfPendingAndRefund(
                    saved.getId(), "AI analysis failed: task queue is full", "ANALYSIS_QUEUE_FULL");
            throw new BusinessException("ANALYSIS_QUEUE_FULL", "AI analysis task queue is full, please retry later");
        }
        return new Submission(AnalysisHistoryResponse.from(saved), false);
    }

    public record Submission(AnalysisHistoryResponse analysis, boolean reusedPending) {
    }
}
