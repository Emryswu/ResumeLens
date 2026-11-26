package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.job.JobDescriptionBulkImportRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionRequest;
import com.arthur.jdragresume.dto.resume.ResumeRequest;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.entity.ResumeChunk;
import com.arthur.jdragresume.rag.LuceneVectorIndex;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeServiceTransactionScopeTests {
    @Test
    void longRunningParsingAndEmbeddingMethodsAreNotAnnotatedWithMethodLevelTransactional() throws Exception {
        assertFalse(ResumeService.class.getMethod("create", ResumeRequest.class)
                .isAnnotationPresent(Transactional.class));
        assertFalse(ResumeService.class.getMethod("upload", MultipartFile.class, String.class, String.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));
        assertFalse(ResumeService.class.getMethod("update", Long.class, ResumeRequest.class)
                .isAnnotationPresent(Transactional.class));
        assertFalse(JobDescriptionService.class.getMethod("bulkImport", JobDescriptionBulkImportRequest.class)
                .isAnnotationPresent(Transactional.class));
    }

    @Test
    void resumeCreateAndUploadPerformParsingAndEmbeddingBeforeStartingTransactionOrLockingUser(@TempDir Path tempDir) {
        List<String> events = new ArrayList<>();
        AppUser user = user();
        RecordingTxManager txManager = new RecordingTxManager(events, false);
        ResumeTextExtractor extractor = new ResumeTextExtractor(new ResumeTextQualityValidator()) {
            @Override
            public String extract(MultipartFile file) {
                events.add("tika");
                return super.extract(file);
            }
        };
        SemanticEmbeddingService embedding = recordingEmbeddingService(events);
        ResumeRepository resumes = proxy(ResumeRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "countByUserId", "sumFileSizeByUserId" -> 0L;
            case "save" -> {
                events.add("save");
                Resume r = (Resume) args[0];
                if (r.getId() == null) ReflectionTestUtils.setField(r, "id", 100L);
                yield r;
            }
            case "toString" -> "ResumeRepositoryScopeDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AppUserRepository users = proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdForUpdate" -> {
                events.add("lockUser");
                yield Optional.of(user);
            }
            case "toString" -> "AppUserRepositoryScopeDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ResumeService service = new ResumeService(
                resumes,
                proxy(ResumeChunkRepository.class, (ignored, method, args) -> null),
                fixedCurrentUser(user, () -> events.add("getCurrentUser")),
                users,
                extractor,
                tempDir.resolve("uploads").toString(),
                null,
                embedding,
                null,
                txManager,
                null
        );

        service.create(new ResumeRequest("Backend", "Arthur", null, null,
                "Java Spring Boot engineer with MySQL and Redis experience."));
        assertEquals(List.of("getCurrentUser", "embed", "txBegin", "lockUser", "save", "txCommit"), events);

        events.clear();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "resume.txt",
                "text/plain",
                "Java Spring Boot engineer with MySQL, Redis, Docker and Kubernetes experience."
                        .getBytes(StandardCharsets.UTF_8)
        );
        service.upload(file, "Uploaded", "Arthur", null, null);
        assertEquals(List.of("getCurrentUser", "tika", "embed", "txBegin", "lockUser", "save", "txCommit"), events);
    }

    @Test
    void resumeUpdateEmbedsBeforeLockingAndKeepsVectorsPublishedAfterCommit(@TempDir Path tempDir) throws Exception {
        List<String> events = new ArrayList<>();
        AppUser user = user();
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.resolve("lucene").toString())) {
            ResumeChunk chunk = new ResumeChunk();
            chunk.setChunkIndex(0);
            chunk.setSourceHash("old-hash");
            chunk.setContent("old content");
            chunk.setEmbedding("[1.0, 0.0, 0.0]");
            index.replace(42L, List.of(chunk));
            assertTrue(index.isCurrent(42L, "old-hash", 1));

            Resume existing = new Resume();
            existing.setUser(user);
            existing.setTitle("Old");
            existing.setCandidateName("Arthur");
            existing.setRawText("old body");
            ReflectionTestUtils.setField(existing, "id", 42L);

            ResumeRepository resumes = proxy(ResumeRepository.class, (ignored, method, args) -> switch (method.getName()) {
                case "findByIdAndUserId" -> Optional.of(existing);
                case "save" -> {
                    events.add("save");
                    yield args[0];
                }
                case "toString" -> "ResumeRepositoryUpdateDouble";
                default -> throw new UnsupportedOperationException(method.getName());
            });
            ResumeChunkRepository chunks = proxy(ResumeChunkRepository.class, (ignored, method, args) -> switch (method.getName()) {
                case "deleteByResumeId" -> {
                    events.add("deleteChunks");
                    yield 1L;
                }
                case "toString" -> "ResumeChunkRepositoryUpdateDouble";
                default -> throw new UnsupportedOperationException(method.getName());
            });
            AppUserRepository users = proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
                case "findByIdForUpdate" -> {
                    events.add("lockUser");
                    yield Optional.of(user);
                }
                case "toString" -> "AppUserRepositoryUpdateDouble";
                default -> throw new UnsupportedOperationException(method.getName());
            });

            RecordingTxManager rollbackTx = new RecordingTxManager(events, true);
            ResumeService rollbackService = new ResumeService(
                    resumes, chunks, fixedCurrentUser(user), users,
                    new ResumeTextExtractor(new ResumeTextQualityValidator()),
                    tempDir.resolve("uploads").toString(), index, recordingEmbeddingService(events),
                    null, rollbackTx, null
            );
            rollbackService.update(42L, new ResumeRequest("New", "Arthur", null, null, "updated resume text"));
            assertTrue(index.isCurrent(42L, "old-hash", 1), "rollback must keep existing Lucene vectors");

            // Simulate DB rollback restoring the persisted entity state before retrying commit.
            existing.setTitle("Old");
            existing.setRawText("old body");
            existing.setSemanticEmbedding(null);
            existing.setSemanticEmbeddingFingerprint(null);
            existing.setSemanticEmbeddingModelKey(null);
            events.clear();
            ResumeChunk rebuiltChunk = new ResumeChunk();
            rebuiltChunk.setChunkIndex(5);
            rebuiltChunk.setSourceHash("new-hash");
            rebuiltChunk.setContent("updated resume text");
            rebuiltChunk.setEmbedding("[0.0, 1.0, 0.0]");
            RecordingTxManager commitTx = new RecordingTxManager(events, false,
                    () -> assertDoesNotThrow(() -> index.replace(42L, List.of(rebuiltChunk))));
            ResumeService commitService = new ResumeService(
                    resumes, chunks, fixedCurrentUser(user), users,
                    new ResumeTextExtractor(new ResumeTextQualityValidator()),
                    tempDir.resolve("uploads").toString(), index, recordingEmbeddingService(events),
                    null, commitTx, null
            );
            commitService.update(42L, new ResumeRequest("New", "Arthur", null, null, "updated resume text"));

            assertEquals(List.of("embed", "txBegin", "lockUser", "deleteChunks", "save", "txCommit"), events);
            assertEquals(List.of(5), index.search(42L, List.of(new float[]{0, 1, 0}), 3)
                    .stream().map(LuceneVectorIndex.VectorHit::chunkIndex).toList());
        }
    }

    @Test
    void jobBulkImportEmbedsBeforeStartingTransactionOrLockingUser() {
        List<String> events = new ArrayList<>();
        AppUser user = user();
        AtomicLong ids = new AtomicLong();
        RecordingTxManager txManager = new RecordingTxManager(events, false);
        JobDescriptionRepository jobs = proxy(JobDescriptionRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "countByUserId" -> {
                events.add("countJobs");
                yield 0L;
            }
            case "save" -> {
                events.add("saveJob");
                JobDescription job = (JobDescription) args[0];
                ReflectionTestUtils.setField(job, "id", ids.incrementAndGet());
                yield job;
            }
            case "toString" -> "JobDescriptionRepositoryScopeDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        AppUserRepository users = proxy(AppUserRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdForUpdate" -> {
                events.add("lockUser");
                yield Optional.of(user);
            }
            case "toString" -> "AppUserRepositoryScopeDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        JobDescriptionService service = new JobDescriptionService(
                jobs,
                fixedCurrentUser(user),
                users,
                recordingEmbeddingService(events),
                null,
                200,
                Clock.systemDefaultZone(),
                txManager
        );

        service.bulkImport(new JobDescriptionBulkImportRequest(List.of(
                new JobDescriptionRequest("Backend A", "Acme", "Remote", "FULL_TIME", "Java", "Spring Boot"),
                new JobDescriptionRequest("Backend B", "Acme", "Remote", "FULL_TIME", "Java", "MySQL")
        )));

        assertEquals(List.of("countJobs", "embed", "txBegin", "lockUser", "countJobs", "saveJob", "saveJob", "txCommit"), events);
    }

    @Test
    void resumeUpdateRejectsMissingEntityAndPreservesEmbeddingAndChunksForContactEdits(@TempDir Path tempDir) {
        List<String> events = new ArrayList<>();
        AppUser user = user();
        SemanticEmbeddingService embedding = recordingEmbeddingService(events);
        Resume existing = new Resume();
        existing.setUser(user);
        existing.setTitle("Backend");
        existing.setCandidateName("Arthur");
        existing.setRawText("Java Spring Boot engineer with MySQL and Redis experience.");
        ReflectionTestUtils.setField(existing, "id", 7L);
        embedding.refresh(existing);
        events.clear();

        ResumeRepository resumes = proxy(ResumeRepository.class, (ignored, method, args) -> switch (method.getName()) {
            case "findByIdAndUserId" -> Long.valueOf(7L).equals(args[0]) ? Optional.of(existing) : Optional.empty();
            case "save" -> {
                events.add("save");
                yield args[0];
            }
            case "toString" -> "ResumeRepositoryPrecheckDouble";
            default -> throw new UnsupportedOperationException(method.getName());
        });
        ResumeService service = new ResumeService(
                resumes,
                proxy(ResumeChunkRepository.class, (ignored, method, args) -> {
                    throw new AssertionError("Contact edits must not touch RAG chunks: " + method.getName());
                }),
                fixedCurrentUser(user),
                proxy(AppUserRepository.class, (ignored, method, args) -> Optional.of(user)),
                new ResumeTextExtractor(new ResumeTextQualityValidator()),
                tempDir.resolve("uploads").toString(),
                null,
                embedding,
                null,
                new RecordingTxManager(events, false),
                null
        );

        org.junit.jupiter.api.Assertions.assertThrows(
                com.arthur.jdragresume.exception.ResourceNotFoundException.class,
                () -> service.update(999L, new ResumeRequest("Backend", "Arthur", null, null, "changed text"))
        );
        assertTrue(events.isEmpty(), "missing resume must fail before running ONNX or opening write tx");

        // Updating only phone/email keeps title + rawText fingerprint intact, skipping ONNX recomputation.
        service.update(7L, new ResumeRequest("Backend", "Arthur", "13800138000", "a@b.com",
                "Java Spring Boot engineer with MySQL and Redis experience."));
        assertEquals(List.of("txBegin", "save", "txCommit"), events);
    }

    private static SemanticEmbeddingService recordingEmbeddingService(List<String> events) {
        EmbeddingModel base = SemanticEmbeddingTestSupport.model(text -> new float[]{1.0f, 0.0f, 0.0f});
        EmbeddingModel recording = new EmbeddingModel() {
            @Override
            public float[] embed(String text) {
                events.add("embed");
                return base.embed(text);
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                events.add("embed");
                return base.embed(texts);
            }

            @Override
            public float[] embed(org.springframework.ai.document.Document document) {
                return embed(document.getText());
            }

            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return base.call(request);
            }
        };
        return new SemanticEmbeddingService(recording, SemanticEmbeddingTestSupport.properties(3));
    }

    private static AppUser user() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    private static CurrentUserService fixedCurrentUser(AppUser user) {
        return fixedCurrentUser(user, () -> {});
    }

    private static CurrentUserService fixedCurrentUser(AppUser user, Runnable onLookup) {
        return new CurrentUserService(null) {
            @Override
            public AppUser getCurrentUser() {
                onLookup.run();
                return user;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RecordingTxManager implements PlatformTransactionManager {
        private final List<String> events;
        private final boolean forceRollback;
        private final Runnable afterDatabaseCommit;

        private RecordingTxManager(List<String> events, boolean forceRollback) {
            this(events, forceRollback, () -> {});
        }

        private RecordingTxManager(List<String> events, boolean forceRollback, Runnable afterDatabaseCommit) {
            this.events = events;
            this.forceRollback = forceRollback;
            this.afterDatabaseCommit = afterDatabaseCommit;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            events.add("txBegin");
            TransactionSynchronizationManager.initSynchronization();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (forceRollback) {
                rollback(status);
                return;
            }
            events.add("txCommit");
            try {
                afterDatabaseCommit.run();
                TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("txRollback");
            try {
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }
}
