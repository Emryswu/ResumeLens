package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeRequest;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.entity.AnalysisStatus;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.common.PageRequests;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.rag.LuceneVectorIndex;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ResumeService {
    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx", "txt", "md");

    private final ResumeRepository resumeRepository;
    private final ResumeChunkRepository resumeChunkRepository;
    private final CurrentUserService currentUserService;
    private final AppUserRepository appUserRepository;
    private final ResumeTextExtractor resumeTextExtractor;
    private final Path resumeUploadDir;
    private final LuceneVectorIndex vectorIndex;
    private final SemanticEmbeddingService semanticEmbeddingService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    @Value("${app.upload.max-resumes-per-user:30}")
    private int maxResumesPerUser = 30;
    @Value("${app.upload.max-stored-bytes-per-user:209715200}")
    private long maxStoredBytesPerUser = 209_715_200L;

    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeChunkRepository resumeChunkRepository,
            CurrentUserService currentUserService,
            AppUserRepository appUserRepository,
            ResumeTextExtractor resumeTextExtractor,
            @Value("${app.upload.resume-dir:uploads/resumes}") String resumeUploadDir,
            LuceneVectorIndex vectorIndex,
            SemanticEmbeddingService semanticEmbeddingService,
            AnalysisHistoryRepository analysisHistoryRepository
    ) {
        this.resumeRepository = resumeRepository;
        this.resumeChunkRepository = resumeChunkRepository;
        this.currentUserService = currentUserService;
        this.appUserRepository = appUserRepository;
        this.resumeTextExtractor = resumeTextExtractor;
        this.resumeUploadDir = Path.of(resumeUploadDir);
        this.vectorIndex = vectorIndex;
        this.semanticEmbeddingService = semanticEmbeddingService;
        this.analysisHistoryRepository = analysisHistoryRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<ResumeResponse> findAll(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        String safeKeyword = normalizeKeyword(keyword);
        var pageRequest = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(resumeRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
                        user.getId(),
                        safeKeyword,
                        user.getId(),
                        safeKeyword,
                        pageRequest
                )
                .map(ResumeResponse::summary));
    }

    /** Agent-only source for bounded direction excerpts; normal list responses still omit the body. */
    @Transactional(readOnly = true)
    public PageResponse<ResumeResponse> findAllForAgent(int page, int size, String keyword) {
        AppUser user = currentUserService.getCurrentUser();
        String safeKeyword = normalizeKeyword(keyword);
        var pageable = PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt", "id"));
        return PageResponse.from(resumeRepository
                .findByUserIdAndTitleContainingIgnoreCaseOrUserIdAndCandidateNameContainingIgnoreCase(
                        user.getId(), safeKeyword, user.getId(), safeKeyword, pageable)
                .map(ResumeResponse::from));
    }

    @Transactional(readOnly = true)
    public ResumeResponse findById(Long id) {
        return ResumeResponse.from(getEntityForCurrentUser(id));
    }

    @Transactional
    public ResumeResponse create(ResumeRequest request) {
        AppUser user = lockCurrentUser();
        enforceResumeCount(user);
        Resume resume = new Resume();
        resume.setUser(user);
        applyRequest(resume, request);
        semanticEmbeddingService.refresh(resume);
        return ResumeResponse.from(resumeRepository.save(resume));
    }

    @Transactional
    public ResumeResponse upload(
            MultipartFile file,
            String title,
            String candidateName,
            String phone,
            String email,
            String rawText
    ) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "resume file must not be empty");
        }

        String originalFileName = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String extension = extractExtension(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("UNSUPPORTED_FILE_TYPE", "only PDF, DOC, DOCX, TXT and MD files are supported");
        }

        validateUploadMetadata(title, candidateName, email);

        String parsedRawText = limitRawText(resolveRawText(file, rawText));
        AppUser user = lockCurrentUser();
        enforceResumeCount(user);
        enforceStoredBytes(user, file.getSize());
        Path userDir = resumeUploadDir.resolve(user.getId().toString());
        String storedName = UUID.randomUUID() + "." + extension;
        Path storedPath = userDir.resolve(storedName);

        boolean stored = false;
        try {
            Files.createDirectories(userDir);
            file.transferTo(storedPath);
            stored = true;
            // 本方法是 @Transactional：save() 的 INSERT 可能推迟到方法返回后由代理 flush，
            // 那时 catch 已经错过了，回滚只撤销数据库行、文件却留在磁盘上。
            deleteFileOnRollback(storedPath);

            Resume resume = new Resume();
            resume.setUser(user);
            resume.setTitle(isBlank(title) ? truncate(originalFileName, 120) : title.trim());
            resume.setCandidateName(candidateName);
            resume.setPhone(phone);
            resume.setEmail(email);
            resume.setOriginalFileName(originalFileName);
            resume.setContentType(file.getContentType());
            resume.setFileExtension(extension);
            resume.setStoredFilePath(storedPath.toString());
            resume.setFileSize(file.getSize());
            resume.setRawText(parsedRawText);
            semanticEmbeddingService.refresh(resume);
            return ResumeResponse.from(resumeRepository.save(resume));
        } catch (BusinessException ex) {
            if (stored) {
                deleteFile(storedPath);
            }
            throw ex;
        } catch (Exception ex) {
            if (stored) {
                deleteFile(storedPath);
            }
            // 原始异常此前被整个吞掉：磁盘满、权限不足、路径不可写在日志里毫无痕迹
            log.error("Failed to store resume file for user {} at {}", user.getId(), storedPath, ex);
            throw new BusinessException("FILE_SAVE_FAILED", "failed to save resume file");
        }
    }

    @Transactional
    public ResumeResponse update(Long id, ResumeRequest request) {
        AppUser user = lockCurrentUser();
        Resume resume = getEntityForUser(id, user.getId());
        applyRequest(resume, request);
        semanticEmbeddingService.refresh(resume);
        resumeChunkRepository.deleteByResumeId(id);
        return ResumeResponse.from(resumeRepository.save(resume));
    }

    @Transactional
    public void delete(Long id) {
        AppUser user = lockCurrentUser();
        Resume resume = getEntityForUser(id, user.getId());
        // analysis_history 对 resume 挂着 ON DELETE CASCADE（V3 迁移）：删掉简历会连带
        // 删掉 PENDING 记录，而 worker 仍在调用模型，于是「进行中」计数凭空减少，
            // 用户可以继续提交，绕过并发上限。lockCurrentUser() 与 AnalysisSubmitGuard
        // 用的是同一把用户行锁，因此「检查」与「删除」之间插不进新的分析。
        if (analysisHistoryRepository.existsByResume_IdAndStatus(id, AnalysisStatus.PENDING)) {
            throw new BusinessException(
                    "ANALYSIS_DELETE_PENDING",
                    "an analysis using this resume is still running, please wait for it to finish"
            );
        }
        Path storedFile = safeStoredFile(resume.getStoredFilePath());
        resumeChunkRepository.deleteByResumeId(id);
        resumeRepository.delete(resume);
        afterCommit(() -> {
            deleteFile(storedFile);
            try {
                vectorIndex.delete(id);
            } catch (Exception ex) {
                log.error("Database row was deleted but vector index cleanup failed for resume {}", id, ex);
            }
        });
    }

    @Transactional(readOnly = true)
    public Resume getEntityForCurrentUser(Long id) {
        AppUser user = currentUserService.getCurrentUser();
        return getEntityForUser(id, user.getId());
    }

    private Resume getEntityForUser(Long id, Long userId) {
        return resumeRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("resume", id));
    }

    private void applyRequest(Resume resume, ResumeRequest request) {
        resume.setTitle(request.title());
        resume.setCandidateName(request.candidateName());
        resume.setPhone(request.phone());
        resume.setEmail(request.email());
        resume.setRawText(limitRawText(request.rawText()));
    }

    private AppUser lockCurrentUser() {
        AppUser user = currentUserService.getCurrentUser();
        return appUserRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new IllegalStateException("current user no longer exists"));
    }

    private void enforceResumeCount(AppUser user) {
        if (resumeRepository.countByUserId(user.getId()) >= maxResumesPerUser) {
            throw new BusinessException(
                    "RESUME_QUOTA_EXCEEDED",
                    "resume count exceeds the maximum of " + maxResumesPerUser
            );
        }
    }

    private void enforceStoredBytes(AppUser user, long incomingBytes) {
        long used = resumeRepository.sumFileSizeByUserId(user.getId());
        if (used + Math.max(0L, incomingBytes) > maxStoredBytesPerUser) {
            throw new BusinessException(
                    "RESUME_STORAGE_QUOTA_EXCEEDED",
                    "stored resume files exceed the maximum of " + maxStoredBytesPerUser + " bytes"
            );
        }
    }

    private void validateUploadMetadata(String title, String candidateName, String email) {
        if (isBlank(candidateName)) {
            throw new BusinessException("VALIDATION_ERROR", "candidateName must not be blank");
        }
        if (candidateName.trim().length() > 80) {
            throw new BusinessException("VALIDATION_ERROR", "candidateName must be at most 80 characters");
        }
        if (title != null && title.trim().length() > 120) {
            throw new BusinessException("VALIDATION_ERROR", "title must be at most 120 characters");
        }
        if (!isBlank(email) && !email.contains("@")) {
            throw new BusinessException("VALIDATION_ERROR", "email must be a valid address");
        }
    }

    private String limitRawText(String rawText) {
        if (rawText != null && rawText.length() > ResumeTextExtractor.MAX_RAW_TEXT_CHARS) {
            throw new BusinessException(
                    "RESUME_TEXT_TOO_LONG",
                    "resume text exceeds the maximum of " + ResumeTextExtractor.MAX_RAW_TEXT_CHARS + " characters"
            );
        }
        return rawText;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String resolveRawText(MultipartFile file, String rawText) {
        if (!isBlank(rawText)) {
            return rawText;
        }
        return resumeTextExtractor.extract(file);
    }

    private String extractExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == filename.length() - 1) {
            throw new BusinessException("MISSING_FILE_EXTENSION", "resume file extension is required");
        }
        return filename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null ? "" : keyword.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Path safeStoredFile(String storedFilePath) {
        if (isBlank(storedFilePath)) {
            return null;
        }
        Path uploadRoot = resumeUploadDir.toAbsolutePath().normalize();
        Path storedFile = Path.of(storedFilePath).toAbsolutePath().normalize();
        if (!storedFile.startsWith(uploadRoot)) {
            log.warn("Refusing to delete resume file outside upload root: {}", storedFile);
            return null;
        }
        return storedFile;
    }

    private void deleteFile(Path storedFile) {
        if (storedFile == null) return;
        try {
            Files.deleteIfExists(storedFile);
        } catch (Exception ex) {
            log.error("Database row was deleted but resume file cleanup failed: {}", storedFile, ex);
        }
    }

    private void deleteFileOnRollback(Path storedFile) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    deleteFile(storedFile);
                }
            }
        });
    }

    private void afterCommit(Runnable cleanup) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
        } else {
            cleanup.run();
        }
    }
}
