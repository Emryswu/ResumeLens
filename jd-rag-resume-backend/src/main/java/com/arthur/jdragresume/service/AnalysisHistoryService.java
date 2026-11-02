package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.dto.analysis.AnalysisHistorySummaryResponse;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.common.PageRequests;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.List;

@Service
public class AnalysisHistoryService {
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final CurrentUserService currentUserService;
    private final ResumeService resumeService;
    private final JobDescriptionService jobDescriptionService;

    public AnalysisHistoryService(
            AnalysisHistoryRepository analysisHistoryRepository,
            CurrentUserService currentUserService,
            ResumeService resumeService,
            JobDescriptionService jobDescriptionService
    ) {
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.currentUserService = currentUserService;
        this.resumeService = resumeService;
        this.jobDescriptionService = jobDescriptionService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnalysisHistoryResponse> findAll(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        var pageRequest = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(analysisHistoryRepository
                .searchByUserId(user.getId(), normalizeKeyword(keyword), pageRequest)
                .map(AnalysisHistoryResponse::listItem));
    }

    @Transactional(readOnly = true)
    public AnalysisHistoryResponse findById(Long id) {
        return AnalysisHistoryResponse.from(getEntityForCurrentUser(id));
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisHistoryResponse> findLatest(Long resumeId, Long jobDescriptionId) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeService.getEntityForCurrentUser(resumeId);
        JobDescription jobDescription = jobDescriptionService.getEntityForCurrentUser(jobDescriptionId);
        return analysisHistoryRepository
                .findFirstByUser_IdAndResume_IdAndJobDescription_IdAndResumeFingerprintAndJobFingerprintOrderByIdDesc(
                        user.getId(),
                        resumeId,
                        jobDescriptionId,
                        ContentFingerprints.resume(resume),
                        ContentFingerprints.job(jobDescription)
                )
                .map(AnalysisHistoryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<AnalysisHistorySummaryResponse> findLatestForEachJob(Long resumeId) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeService.getEntityForCurrentUser(resumeId);
        return analysisHistoryRepository
                .findLatestCurrentForEachJobByUserIdAndResumeId(
                        user.getId(),
                        resumeId,
                        ContentFingerprints.resume(resume)
                );
    }

    @Transactional
    public void delete(Long id) {
        AnalysisHistory history = getEntityForCurrentUser(id);
        // 删掉一条还在跑的记录并不会停掉 worker，只会把它从「进行中」计数里抹掉，
        // 从而绕过并发上限；且事后 worker 回写会落到一条已不存在的记录上。
        if (history.getStatus() == AnalysisStatus.PENDING) {
            throw new BusinessException(
                    "ANALYSIS_DELETE_PENDING",
                    "analysis is still running, please wait for it to finish before deleting"
            );
        }
        analysisHistoryRepository.delete(history);
    }

    @Transactional(readOnly = true)
    public AnalysisHistory getEntityForCurrentUser(Long id) {
        AppUser user = currentUserService.getCurrentUser();
        return analysisHistoryRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("analysis history", id));
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }
}
