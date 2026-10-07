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
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalysisSubmitGuard {
    /** All submissions in the window, refunded or not, may not exceed this many times the normal limit. */
    static final int HARD_CAP_MULTIPLIER = 2;

    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final AnalysisSubmissionLogRepository analysisSubmissionLogRepository;
    private final AnalysisSubmissionRefundRepository analysisSubmissionRefundRepository;
    private final AppUserRepository appUserRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobDescriptionRepository;
    private final int maxPendingPerUser;
    private final int maxSubmitsPerWindow;
    private final long submitWindowMinutes;

    public AnalysisSubmitGuard(
            AnalysisHistoryRepository analysisHistoryRepository,
            AnalysisSubmissionLogRepository analysisSubmissionLogRepository,
            AnalysisSubmissionRefundRepository analysisSubmissionRefundRepository,
            AppUserRepository appUserRepository,
            ResumeRepository resumeRepository,
            JobDescriptionRepository jobDescriptionRepository,
            @Value("${app.analysis.max-pending-per-user:2}") int maxPendingPerUser,
            @Value("${app.analysis.max-submits-per-window:10}") int maxSubmitsPerWindow,
            @Value("${app.analysis.submit-window-minutes:10}") long submitWindowMinutes
    ) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.analysisSubmissionLogRepository = analysisSubmissionLogRepository;
        this.analysisSubmissionRefundRepository = analysisSubmissionRefundRepository;
        this.appUserRepository = appUserRepository;
        this.resumeRepository = resumeRepository;
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.maxPendingPerUser = Math.max(1, maxPendingPerUser);
        this.maxSubmitsPerWindow = Math.max(1, maxSubmitsPerWindow);
        this.submitWindowMinutes = Math.max(1, submitWindowMinutes);
    }

    @Transactional
    public Admission admit(AppUser user, Long resumeId, Long jobDescriptionId) {
        AppUser lockedUser = appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, lockedUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("resume", resumeId));
        JobDescription jobDescription = jobDescriptionRepository
                .findByIdAndUserId(jobDescriptionId, lockedUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("job description", jobDescriptionId));
        if (resume.getRawText() == null || resume.getRawText().isBlank()) {
            throw new BusinessException("RESUME_TEXT_EMPTY", "resume rawText is empty");
        }

        String resumeFingerprint = ContentFingerprints.resume(resume);
        String jobFingerprint = ContentFingerprints.job(jobDescription);
        if (!jobFingerprint.equals(jobDescription.getContentFingerprint())) {
            jobDescription.setContentFingerprint(jobFingerprint);
        }

        var existingPending = analysisHistoryRepository
                .findFirstByUser_IdAndResume_IdAndJobDescription_IdAndStatusAndResumeFingerprintAndJobFingerprintOrderByIdDesc(
                        lockedUser.getId(),
                        resume.getId(),
                        jobDescription.getId(),
                        AnalysisStatus.PENDING,
                        resumeFingerprint,
                        jobFingerprint
                );
        if (existingPending.isPresent()) {
            return new Admission(existingPending.get(), true);
        }

        long pending = analysisHistoryRepository.countByUser_IdAndStatus(
                lockedUser.getId(),
                AnalysisStatus.PENDING
        );
        if (pending >= maxPendingPerUser) {
            throw new BusinessException(
                    "ANALYSIS_TOO_MANY_PENDING",
                    "too many analyses are already running, please wait for one to finish"
            );
        }

        // 限流计数取自只追加的 analysis_submission_log，而不是可被用户删除的
        // analysis_history；否则「删历史 → 重提」就能把窗口内的计数清掉。
        // 因模型输出不合规而失败的提交会被退还，不占计数；但退还过的也算进一道
        // 更高的硬上限，否则在简历里写注入文字故意让模型输出不合规，就能无限调用模型。
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(submitWindowMinutes);
        long submitted = analysisSubmissionLogRepository
                .countByUser_IdAndCreatedAtAfter(lockedUser.getId(), cutoff);
        long refunded = analysisSubmissionRefundRepository
                .countRefundedSubmissions(lockedUser.getId(), cutoff);
        if (submitted - refunded >= maxSubmitsPerWindow
                || submitted >= (long) maxSubmitsPerWindow * HARD_CAP_MULTIPLIER) {
            throw new BusinessException(
                    "ANALYSIS_RATE_LIMITED",
                    "too many analysis requests, please retry later"
            );
        }

        AnalysisSubmissionLog submissionLog = new AnalysisSubmissionLog();
        submissionLog.setUser(lockedUser);
        submissionLog = analysisSubmissionLogRepository.save(submissionLog);

        AnalysisHistory history = new AnalysisHistory();
        history.setSubmissionLogId(submissionLog.getId());
        history.setUser(lockedUser);
        history.setResume(resume);
        history.setJobDescription(jobDescription);
        history.setResumeFingerprint(resumeFingerprint);
        history.setJobFingerprint(jobFingerprint);
        history.setStatus(AnalysisStatus.PENDING);
        history.setSummary("AI analysis is pending");
        return new Admission(analysisHistoryRepository.save(history), false);
    }

    public record Admission(AnalysisHistory history, boolean reusedPending) {
    }
}
