package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.JobSourceLookupResponse;
import com.arthur.jdragresume.dto.job.ListingCaptureResult;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.CaptureDepth;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.common.PageRequests;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

@Service
public class JobDescriptionService {
    private final JobDescriptionRepository jobDescriptionRepository;
    private final CurrentUserService currentUserService;
    private final AppUserRepository appUserRepository;
    private final SemanticEmbeddingService semanticEmbeddingService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final int maxJobDescriptionsPerUser;
    private final Clock clock;

    public JobDescriptionService(
            JobDescriptionRepository jobDescriptionRepository,
            CurrentUserService currentUserService,
            AppUserRepository appUserRepository,
            SemanticEmbeddingService semanticEmbeddingService,
            AnalysisHistoryRepository analysisHistoryRepository,
            @Value("${app.job-description.max-per-user:200}") int maxJobDescriptionsPerUser,
            Clock clock
    ) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.currentUserService = currentUserService;
        this.appUserRepository = appUserRepository;
        this.semanticEmbeddingService = semanticEmbeddingService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.maxJobDescriptionsPerUser = Math.max(1, maxJobDescriptionsPerUser);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<JobDescriptionResponse> findAll(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        String safeKeyword = normalizeKeyword(keyword);
        var pageRequest = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(jobDescriptionRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCompanyNameContainingIgnoreCase(
                        user.getId(),
                        safeKeyword,
                        user.getId(),
                        safeKeyword,
                        pageRequest
                )
                .map(JobDescriptionResponse::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<JobDescriptionResponse> findAll(int page, int size, String keyword, String employmentType, String collectionExperience) {
        AppUser user = currentUserService.getCurrentUser();
        var pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(jobDescriptionRepository.searchForUser(user.getId(), normalizeKeyword(keyword),
                normalizeKeyword(employmentType), normalizeKeyword(collectionExperience), pageable).map(JobDescriptionResponse::from));
    }

    @Transactional(readOnly = true)
    public LibraryCounts libraryCounts() {
        Long userId = currentUserService.getCurrentUser().getId();
        java.util.Map<String, Long> counts = new java.util.TreeMap<>();
        jobDescriptionRepository.countTypesForUser(userId).forEach(row -> counts.put(row.getEmploymentType(), row.getTotal()));
        return new LibraryCounts(counts.values().stream().mapToLong(Long::longValue).sum(), counts);
    }

    public record LibraryCounts(long total, java.util.Map<String, Long> byEmploymentType) { }

    @Transactional(readOnly = true)
    public JobDescriptionResponse findById(Long id) {
        return JobDescriptionResponse.from(getEntityForCurrentUser(id));
    }

    @Transactional
    public JobDescriptionResponse create(JobDescriptionRequest request) {
        AppUser user = lockCurrentUser();
        enforceJobDescriptionCount(user, 1);
        JobDescription jobDescription = new JobDescription();
        jobDescription.setUser(user);
        applyRequest(jobDescription, request);
        jobDescription.setContentFingerprint(ContentFingerprints.job(jobDescription));
        semanticEmbeddingService.refresh(jobDescription);
        return JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription));
    }

    @Transactional
    public List<JobDescriptionResponse> bulkImport(JobDescriptionBulkImportRequest request) {
        AppUser user = lockCurrentUser();
        enforceJobDescriptionCount(user, request.items().size());
        List<JobDescription> jobs = request.items().stream()
                .map(item -> {
                    JobDescription jobDescription = new JobDescription();
                    jobDescription.setUser(user);
                    applyRequest(jobDescription, item);
                    jobDescription.setContentFingerprint(ContentFingerprints.job(jobDescription));
                    return jobDescription;
                })
                .toList();
        semanticEmbeddingService.refreshJobs(jobs);
        return jobs.stream()
                .map(jobDescriptionRepository::save)
                .map(JobDescriptionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public JobSourceLookupResponse findBySource(String sourcePlatform, String sourceJobId) {
        AppUser user = currentUserService.getCurrentUser();
        return jobDescriptionRepository.findByUserIdAndSourcePlatformAndSourceJobId(
                        user.getId(),
                        normalizeSourcePlatform(sourcePlatform),
                        normalizeSourceJobId(sourceJobId)
                )
                .map(JobDescriptionResponse::from)
                .map(JobSourceLookupResponse::found)
                .orElseGet(JobSourceLookupResponse::missing);
    }

    @Transactional
    public JobCaptureResponse capture(JobCaptureRequest request) {
        AppUser user = lockCurrentUser();
        String sourcePlatform = normalizeSourcePlatform(request.sourcePlatform());
        String sourceJobId = normalizeSourceJobId(request.sourceJobId());
        String fingerprint = ContentFingerprints.job(
                request.title(),
                request.companyName(),
                request.location(),
                request.employmentType(),
                request.description(),
                request.requirements()
        );
        LocalDateTime now = LocalDateTime.now();

        var existing = jobDescriptionRepository.findByUserIdAndSourcePlatformAndSourceJobId(
                user.getId(),
                sourcePlatform,
                sourceJobId
        );
        if (existing.isPresent()) {
            JobDescription jobDescription = existing.get();
            boolean contentChanged = !fingerprint.equals(jobDescription.getContentFingerprint());
            if (contentChanged) {
                applyCaptureRequest(jobDescription, request);
                jobDescription.setContentFingerprint(fingerprint);
                semanticEmbeddingService.refresh(jobDescription);
            }
            // A detail-page capture is the full posting, also when it replaces a list summary.
            jobDescription.setCaptureDepth(CaptureDepth.FULL);
            jobDescription.setFullVerifiedAt(LocalDateTime.now(clock));
            jobDescription.setSourceUrl(request.sourceUrl().trim());
            jobDescription.setLastSeenAt(now);
            JobDescription saved = jobDescriptionRepository.save(jobDescription);
            return new JobCaptureResponse(JobDescriptionResponse.from(saved), true, contentChanged);
        }

        enforceJobDescriptionCount(user, 1);
        JobDescription jobDescription = new JobDescription();
        jobDescription.setUser(user);
        applyCaptureRequest(jobDescription, request);
        jobDescription.setSourcePlatform(sourcePlatform);
        jobDescription.setSourceUrl(request.sourceUrl().trim());
        jobDescription.setSourceJobId(sourceJobId);
        jobDescription.setContentFingerprint(fingerprint);
        jobDescription.setLastSeenAt(now);
        jobDescription.setFullVerifiedAt(LocalDateTime.now(clock));
        semanticEmbeddingService.refresh(jobDescription);
        JobDescription saved = jobDescriptionRepository.save(jobDescription);
        return new JobCaptureResponse(JobDescriptionResponse.from(saved), false, false);
    }

    /**
     * Saves search-list summaries collected by the assistant. Unlike {@link #capture}, a listing
     * never overwrites what the library already has for the same source job: a summary is less
     * than any posting already stored, so a known job only gets its last-seen time and collection filter refreshed.
     * New jobs are saved in order until the quota is reached; the rest are counted, not saved.
     */
    @Transactional
    public ListingCaptureResult captureListings(List<JobCaptureRequest> listings, String collectionExperience) {
        AppUser user = lockCurrentUser();
        LocalDateTime now = LocalDateTime.now();
        long count = jobDescriptionRepository.countByUserId(user.getId());
        List<ListingCaptureResult.Saved> saved = new ArrayList<>();
        List<JobDescription> created = new ArrayList<>();
        int notSaved = 0;
        Set<String> seen = new HashSet<>();
        for (JobCaptureRequest request : listings) {
            String sourcePlatform = normalizeSourcePlatform(request.sourcePlatform());
            String sourceJobId = normalizeSourceJobId(request.sourceJobId());
            if (!seen.add(sourcePlatform + ":" + sourceJobId)) {
                continue;
            }
            var existing = jobDescriptionRepository.findByUserIdAndSourcePlatformAndSourceJobId(
                    user.getId(), sourcePlatform, sourceJobId);
            if (existing.isPresent()) {
                JobDescription jobDescription = existing.get();
                jobDescription.setLastSeenAt(now);
                jobDescription.setCollectionExperience(collectionExperience);
                saved.add(new ListingCaptureResult.Saved(JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription)), true));
                continue;
            }
            if (count >= maxJobDescriptionsPerUser) {
                notSaved++;
                continue;
            }
            JobDescription jobDescription = new JobDescription();
            jobDescription.setUser(user);
            applyCaptureRequest(jobDescription, request);
            jobDescription.setSourcePlatform(sourcePlatform);
            jobDescription.setSourceUrl(request.sourceUrl().trim());
            jobDescription.setSourceJobId(sourceJobId);
            jobDescription.setContentFingerprint(ContentFingerprints.job(jobDescription));
            jobDescription.setCaptureDepth(CaptureDepth.LISTING);
            jobDescription.setCollectionExperience(collectionExperience);
            jobDescription.setLastSeenAt(now);
            created.add(jobDescription);
            count++;
        }
        // One batch embedding call for every new summary, as bulkImport does.
        semanticEmbeddingService.refreshJobs(created);
        for (JobDescription jobDescription : created) {
            saved.add(new ListingCaptureResult.Saved(JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription)), false));
        }
        return new ListingCaptureResult(saved, notSaved, notSaved > 0, maxJobDescriptionsPerUser);
    }

    @Transactional
    public JobDescriptionResponse update(Long id, JobDescriptionRequest request) {
        AppUser user = lockCurrentUser();
        JobDescription jobDescription = getEntityForUser(id, user.getId());
        applyRequest(jobDescription, request);
        jobDescription.setContentFingerprint(ContentFingerprints.job(jobDescription));
        semanticEmbeddingService.refresh(jobDescription);
        return JobDescriptionResponse.from(jobDescriptionRepository.save(jobDescription));
    }

    @Transactional
    public void delete(Long id) {
        AppUser user = lockCurrentUser();
        JobDescription jobDescription = getEntityForUser(id, user.getId());
        // 与 ResumeService.delete() 同理：analysis_history 对 job_description 挂着
        // ON DELETE CASCADE（V3 迁移），删岗位会连带删掉 PENDING 记录，worker
        // 却仍在跑，于是「进行中」计数凭空减少。锁用的是与 AnalysisSubmitGuard
        // 相同的用户行锁，保证检查与删除之间插不进新的提交。
        if (analysisHistoryRepository.existsByJobDescription_IdAndStatus(id, AnalysisStatus.PENDING)) {
            throw new BusinessException(
                    "ANALYSIS_DELETE_PENDING",
                    "an analysis using this job description is still running, please wait for it to finish"
            );
        }
        jobDescriptionRepository.delete(jobDescription);
    }

    /**
     * 与 ResumeService 一样先锁住用户行再计数：否则两个并发的 /import
     * 会各自读到配额用尽前的旧计数，双双通过校验后把上限顶穿。
     */
    private AppUser lockCurrentUser() {
        AppUser user = currentUserService.getCurrentUser();
        return appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));
    }

    /**
     * 简历侧有份数与字节数双重配额，岗位侧此前一份都没有：单条 JD 上限约 4 万字符，
     * /import 一次 50 条且可无限次调用，任何登录用户都能把存储撑满。
     */
    private void enforceJobDescriptionCount(AppUser user, int incoming) {
        long existing = jobDescriptionRepository.countByUserId(user.getId());
        if (existing + incoming > maxJobDescriptionsPerUser) {
            throw new BusinessException(
                    "JOB_DESCRIPTION_QUOTA_EXCEEDED",
                    "job description count exceeds the maximum of " + maxJobDescriptionsPerUser
            );
        }
    }

    @Transactional(readOnly = true)
    public JobDescription getEntityForCurrentUser(Long id) {
        AppUser user = currentUserService.getCurrentUser();
        return getEntityForUser(id, user.getId());
    }

    private JobDescription getEntityForUser(Long id, Long userId) {
        return jobDescriptionRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("job description", id));
    }

    private void applyRequest(JobDescription jobDescription, JobDescriptionRequest request) {
        jobDescription.setTitle(request.title());
        jobDescription.setCompanyName(request.companyName());
        jobDescription.setLocation(request.location());
        jobDescription.setEmploymentType(request.employmentType());
        jobDescription.setDescription(request.description());
        jobDescription.setRequirements(request.requirements());
    }

    private void applyCaptureRequest(JobDescription jobDescription, JobCaptureRequest request) {
        jobDescription.setTitle(request.title().trim());
        jobDescription.setCompanyName(request.companyName().trim());
        jobDescription.setLocation(trimToNull(request.location()));
        jobDescription.setEmploymentType(trimToNull(request.employmentType()));
        jobDescription.setDescription(request.description().trim());
        jobDescription.setRequirements(trimToNull(request.requirements()));
    }

    private String normalizeSourcePlatform(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || normalized.length() > 32) {
            throw new IllegalArgumentException("sourcePlatform must contain 1 to 32 characters");
        }
        return normalized;
    }

    private String normalizeSourceJobId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 160) {
            throw new IllegalArgumentException("sourceJobId must contain 1 to 160 characters");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }
}
