package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class JobSemanticMatchService {
    private static final int MAX_MATCHES = 200;

    private final JobDescriptionRepository jobDescriptionRepository;
    private final ResumeRepository resumeRepository;
    private final CurrentUserService currentUserService;
    private final SemanticEmbeddingService semanticEmbeddingService;

    public JobSemanticMatchService(
            JobDescriptionRepository jobDescriptionRepository,
            ResumeRepository resumeRepository,
            CurrentUserService currentUserService,
            SemanticEmbeddingService semanticEmbeddingService
    ) {
        this.jobDescriptionRepository = jobDescriptionRepository;
        this.resumeRepository = resumeRepository;
        this.currentUserService = currentUserService;
        this.semanticEmbeddingService = semanticEmbeddingService;
    }

    /**
     * Performs a read-only in-memory cosine sort over the current embedding cache.
     * With the enforced 200-JD user quota this is intentionally simpler than a vector database.
     */
    @Transactional(readOnly = true)
    public List<JobSemanticMatchResponse> rank(Long resumeId, int limit) {
        return rank(resumeId, limit, null);
    }

    /**
     * Ranks only the given jobs when {@code jobIds} is not null (ids that are not the user's are
     * simply absent from the result), otherwise the whole library.
     */
    @Transactional(readOnly = true)
    public List<JobSemanticMatchResponse> rank(Long resumeId, int limit, Collection<Long> jobIds) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("resume", resumeId));
        requireResumeText(resume);

        List<JobDescription> jobs = jobDescriptionRepository.findAllByUserId(user.getId());
        if (jobIds != null) {
            Set<Long> wanted = Set.copyOf(jobIds);
            jobs = jobs.stream().filter(job -> wanted.contains(job.getId())).toList();
        }
        if (!semanticEmbeddingService.isCurrent(resume)
                || jobs.stream().anyMatch(job -> !semanticEmbeddingService.isCurrent(job))) {
            throw new BusinessException(
                    "SEMANTIC_EMBEDDING_STALE",
                    "semantic ranking embeddings are stale; refresh them before matching"
            );
        }

        int safeLimit = Math.max(1, Math.min(MAX_MATCHES, limit));
        return jobs.stream()
                .map(job -> JobSemanticMatchResponse.from(
                        job,
                        semanticEmbeddingService.similarity(resume, job)
                ))
                .sorted(Comparator
                        .comparingDouble(JobSemanticMatchResponse::similarity)
                        .reversed()
                        .thenComparing(match -> match.job().id(), Comparator.reverseOrder()))
                .limit(safeLimit)
                .toList();
    }

    /**
     * Explicit write path used only when the read endpoint reports a stale derived cache.
     */
    @Transactional
    public void refreshStaleEmbeddings(Long resumeId) {
        AppUser user = currentUserService.getCurrentUser();
        Resume resume = resumeRepository.findByIdAndUserId(resumeId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("resume", resumeId));
        requireResumeText(resume);

        if (!semanticEmbeddingService.isCurrent(resume)) {
            semanticEmbeddingService.refresh(resume);
            resumeRepository.save(resume);
        }

        List<JobDescription> staleJobs = jobDescriptionRepository.findAllByUserId(user.getId()).stream()
                .filter(job -> !semanticEmbeddingService.isCurrent(job))
                .toList();
        if (!staleJobs.isEmpty()) {
            semanticEmbeddingService.refreshJobs(staleJobs);
            jobDescriptionRepository.saveAll(staleJobs);
        }
    }

    private void requireResumeText(Resume resume) {
        if (resume.getRawText() == null || resume.getRawText().isBlank()) {
            throw new BusinessException("RESUME_TEXT_EMPTY", "resume rawText is empty");
        }
    }
}
