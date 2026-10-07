package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AnalysisSubmissionRefund;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AnalysisSubmissionRefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

@Service
public class AnalysisHistoryUpdateService {
    private final AnalysisHistoryRepository historyRepository;
    private final AnalysisSubmissionRefundRepository refundRepository;

    public AnalysisHistoryUpdateService(
            AnalysisHistoryRepository historyRepository,
            AnalysisSubmissionRefundRepository refundRepository
    ) {
        this.historyRepository = historyRepository;
        this.refundRepository = refundRepository;
    }

    @Transactional
    public boolean completeIfPending(Long id, Consumer<AnalysisHistory> mutator) {
        AnalysisHistory history = historyRepository.findByIdForUpdate(id).orElse(null);
        if (history == null || history.getStatus() != AnalysisStatus.PENDING) {
            return false;
        }
        if (!ContentFingerprints.inputsMatch(history)) {
            history.setStatus(AnalysisStatus.FAILED);
            history.setSummary("分析期间简历或岗位内容已更新，请基于最新内容重新分析");
            return false;
        }
        mutator.accept(history);
        history.setStatus(AnalysisStatus.COMPLETED);
        return true;
    }

    @Transactional
    public boolean failIfPending(Long id, String summary) {
        AnalysisHistory history = historyRepository.findByIdForUpdate(id).orElse(null);
        if (history == null || history.getStatus() != AnalysisStatus.PENDING) {
            return false;
        }
        history.setStatus(AnalysisStatus.FAILED);
        history.setSummary(summary);
        return true;
    }

    /**
     * Fails the analysis and, in the same transaction, gives the submission back to the user's rate limit.
     * Only for failures the user did not cause (the model's output could not be used).
     */
    @Transactional
    public boolean failIfPendingAndRefund(Long id, String summary, String reason) {
        AnalysisHistory history = historyRepository.findByIdForUpdate(id).orElse(null);
        if (history == null || history.getStatus() != AnalysisStatus.PENDING) {
            return false;
        }
        history.setStatus(AnalysisStatus.FAILED);
        history.setSummary(summary);
        Long submissionLogId = history.getSubmissionLogId();
        if (submissionLogId != null && !refundRepository.existsBySubmissionLogId(submissionLogId)) {
            AnalysisSubmissionRefund refund = new AnalysisSubmissionRefund();
            refund.setSubmissionLogId(submissionLogId);
            refund.setReason(reason);
            refundRepository.save(refund);
        }
        return true;
    }
}
