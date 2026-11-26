package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.common.LogSafe;
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
import com.arthur.jdragresume.rag.ResumeVectorStore;
import com.arthur.jdragresume.security.CurrentUserService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
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
    private final ResumeFileStorage resumeFileStorage;
    private final ResumeVectorStore vectorIndex;
    private final SemanticEmbeddingService semanticEmbeddingService;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final TransactionTemplate transactionTemplate;
    private final Validator requestValidator;
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
            String resumeUploadDir,
            ResumeVectorStore vectorIndex,
            SemanticEmbeddingService semanticEmbeddingService,
            AnalysisHistoryRepository analysisHistoryRepository
    ) {
        this(
                resumeRepository,
                resumeChunkRepository,
                currentUserService,
                appUserRepository,
                resumeTextExtractor,
                resumeUploadDir,
                vectorIndex,
                semanticEmbeddingService,
                analysisHistoryRepository,
                null,
                null
        );
    }

    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeChunkRepository resumeChunkRepository,
            CurrentUserService currentUserService,
            AppUserRepository appUserRepository,
            ResumeTextExtractor resumeTextExtractor,
            String resumeUploadDir,
            ResumeVectorStore vectorIndex,
            SemanticEmbeddingService semanticEmbeddingService,
            AnalysisHistoryRepository analysisHistoryRepository,
            PlatformTransactionManager transactionManager,
            Validator requestValidator
    ) {
        this(
                resumeRepository,
                resumeChunkRepository,
                currentUserService,
                appUserRepository,
                resumeTextExtractor,
                new LocalResumeFileStorage(resumeUploadDir),
                vectorIndex,
                semanticEmbeddingService,
                analysisHistoryRepository,
                transactionManager,
                requestValidator
        );
    }

    @Autowired
    public ResumeService(
            ResumeRepository resumeRepository,
            ResumeChunkRepository resumeChunkRepository,
            CurrentUserService currentUserService,
            AppUserRepository appUserRepository,
            ResumeTextExtractor resumeTextExtractor,
            ResumeFileStorage resumeFileStorage,
            ResumeVectorStore vectorIndex,
            SemanticEmbeddingService semanticEmbeddingService,
            AnalysisHistoryRepository analysisHistoryRepository,
            PlatformTransactionManager transactionManager,
            Validator requestValidator
    ) {
        this.resumeRepository = resumeRepository;
        this.resumeChunkRepository = resumeChunkRepository;
        this.currentUserService = currentUserService;
        this.appUserRepository = appUserRepository;
        this.resumeTextExtractor = resumeTextExtractor;
        this.resumeFileStorage = resumeFileStorage;
        this.vectorIndex = vectorIndex;
        this.semanticEmbeddingService = semanticEmbeddingService;
        this.analysisHistoryRepository = analysisHistoryRepository;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.requestValidator = requestValidator != null ? requestValidator : DefaultValidatorHolder.VALIDATOR;
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

    public ResumeResponse create(ResumeRequest request) {
        AppUser precheckUser = currentUserService.getCurrentUser();
        enforceResumeCount(precheckUser);
        Resume resume = new Resume();
        applyRequest(resume, request);
        semanticEmbeddingService.refresh(resume);
        return inTransaction(() -> {
            AppUser user = lockUser(precheckUser.getId());
            enforceResumeCount(user);
            resume.setUser(user);
            return ResumeResponse.from(resumeRepository.save(resume));
        });
    }

    public ResumeResponse upload(
            MultipartFile file,
            String title,
            String candidateName,
            String phone,
            String email
    ) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("EMPTY_FILE", "resume file must not be empty");
        }

        String originalFileName = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        if (originalFileName.length() > 255) {
            throw new BusinessException("VALIDATION_ERROR", "originalFileName 不能超过 255 个字符");
        }
        String contentType = file.getContentType();
        if (contentType != null && contentType.length() > 120) {
            throw new BusinessException("VALIDATION_ERROR", "Content-Type 不能超过 120 个字符");
        }
        String extension = extractExtension(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("UNSUPPORTED_FILE_TYPE", "only PDF, DOC, DOCX, TXT and MD files are supported");
        }

        AppUser precheckUser = currentUserService.getCurrentUser();
        enforceResumeCount(precheckUser);
        enforceStoredBytes(precheckUser, file.getSize());

        String parsedRawText = limitRawText(resumeTextExtractor.extract(file));
        ResumeRequest request = new ResumeRequest(
                isBlank(title) ? truncate(originalFileName, 120) : title.trim(),
                candidateName, phone, email, parsedRawText);
        validateRequest(request);

        Resume resume = new Resume();
        applyRequest(resume, request);
        resume.setOriginalFileName(originalFileName);
        resume.setContentType(contentType);
        resume.setFileExtension(extension);
        resume.setFileSize(file.getSize());
        semanticEmbeddingService.refresh(resume);

        return inTransaction(() -> {
            AppUser user = lockUser(precheckUser.getId());
            enforceResumeCount(user);
            enforceStoredBytes(user, file.getSize());
            String storedLocation = null;
            try {
                storedLocation = resumeFileStorage.store(user.getId(), extension, file);
                // save() 的 INSERT 可能推迟到事务提交 flush 时执行，回滚同步器保证文件不残留。
                deleteFileOnRollback(storedLocation);

                resume.setUser(user);
                resume.setStoredFilePath(storedLocation);
                return ResumeResponse.from(resumeRepository.save(resume));
            } catch (BusinessException ex) {
                if (storedLocation != null) {
                    resumeFileStorage.delete(storedLocation);
                }
                throw ex;
            } catch (Exception ex) {
                if (storedLocation != null) {
                    resumeFileStorage.delete(storedLocation);
                }
                // 原始异常此前被整个吞掉：磁盘满、权限不足、路径不可写在日志里毫无痕迹
                log.error("Failed to store resume file for user {} at {}: {}", user.getId(), storedLocation, LogSafe.describe(ex));
                throw new BusinessException("FILE_SAVE_FAILED", "failed to save resume file");
            }
        });
    }

    public ResumeResponse update(Long id, ResumeRequest request) {
        AppUser precheckUser = currentUserService.getCurrentUser();
        Resume existing = getEntityForUser(id, precheckUser.getId());
        Resume computed = new Resume();
        applyRequest(computed, request);
        computed.setSemanticEmbedding(existing.getSemanticEmbedding());
        computed.setSemanticEmbeddingFingerprint(existing.getSemanticEmbeddingFingerprint());
        computed.setSemanticEmbeddingModelKey(existing.getSemanticEmbeddingModelKey());
        if (!semanticEmbeddingService.isCurrent(computed)) {
            semanticEmbeddingService.refresh(computed);
        }
        return inTransaction(() -> {
            AppUser user = lockCurrentUser();
            Resume resume = getEntityForUser(id, user.getId());
            boolean rawTextChanged = !Objects.equals(resume.getRawText(), computed.getRawText());
            applyRequest(resume, request);
            resume.setSemanticEmbedding(computed.getSemanticEmbedding());
            resume.setSemanticEmbeddingFingerprint(computed.getSemanticEmbeddingFingerprint());
            resume.setSemanticEmbeddingModelKey(computed.getSemanticEmbeddingModelKey());
            if (rawTextChanged) {
                resumeChunkRepository.deleteByResumeId(id);
            }
            // Retrieval checks the Lucene source hash and replaces the old index when it rebuilds the chunks.
            return ResumeResponse.from(resumeRepository.save(resume));
        });
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
        String storedFilePath = resume.getStoredFilePath();
        resumeChunkRepository.deleteByResumeId(id);
        resumeRepository.delete(resume);
        afterCommit(() -> {
            resumeFileStorage.delete(storedFilePath);
            deleteVectorIndex(id);
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
        return lockUser(currentUserService.getCurrentUser().getId());
    }

    private AppUser lockUser(Long userId) {
        return appUserRepository.findByIdForUpdate(userId)
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

    private void validateRequest(ResumeRequest request) {
        var violations = requestValidator.validate(request);
        if (!violations.isEmpty()) {
            var violation = violations.iterator().next();
            throw new BusinessException("VALIDATION_ERROR",
                    "简历字段 " + violation.getPropertyPath() + " 格式或长度不符合要求");
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        if (transactionTemplate == null) {
            return action.get();
        }
        return transactionTemplate.execute(status -> action.get());
    }

    private void deleteVectorIndex(Long id) {
        if (vectorIndex == null) {
            return;
        }
        try {
            vectorIndex.delete(id);
        } catch (Exception ex) {
            log.error("Database change succeeded but vector index cleanup failed for resume {}: {}", id, LogSafe.describe(ex));
        }
    }

    private static final class DefaultValidatorHolder {
        private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
        private static final Validator VALIDATOR = FACTORY.getValidator();

        static {
            Runtime.getRuntime().addShutdownHook(new Thread(FACTORY::close, "resume-validator-factory-shutdown"));
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

    private void deleteFileOnRollback(String storedLocation) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    resumeFileStorage.delete(storedLocation);
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
