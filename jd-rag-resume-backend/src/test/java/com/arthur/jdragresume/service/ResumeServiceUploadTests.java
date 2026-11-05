package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeServiceUploadTests {
    @Test
    void rejectsCreateWhenResumeCountQuotaIsExceeded(@TempDir Path tempDir) {
        ResumeService service = service(tempDir, 30L, 0L, Optional.empty());
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.create(new com.arthur.jdragresume.dto.resume.ResumeRequest(
                        "title",
                        "Arthur",
                        null,
                        null,
                        "Java backend engineer with Spring Boot and MySQL experience."
                ))
        );
        assertEquals("RESUME_QUOTA_EXCEEDED", exception.getCode());
    }

    @Test
    void rejectsUploadWhenResumeCountQuotaIsExceeded(@TempDir Path tempDir) {
        ResumeService service = service(tempDir, 30L, 0L, Optional.empty());
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(sampleFile(), "title", "Arthur", null, null)
        );
        assertEquals("RESUME_QUOTA_EXCEEDED", exception.getCode());
    }

    @Test
    void rejectsUploadWhenStoredBytesQuotaIsExceeded(@TempDir Path tempDir) {
        ResumeService service = service(tempDir, 0L, 209_715_200L, Optional.empty());
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(sampleFile(), "title", "Arthur", null, null)
        );
        assertEquals("RESUME_STORAGE_QUOTA_EXCEEDED", exception.getCode());
    }

    @Test
    void deletesOrphanFileWhenDatabaseSaveFails(@TempDir Path tempDir) throws Exception {
        ResumeService service = service(tempDir, 0L, 0L, Optional.of(new RuntimeException("db down")));
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.upload(sampleFile(), "title", "Arthur", null, null)
        );
        assertEquals("FILE_SAVE_FAILED", exception.getCode());
        Path userDir = tempDir.resolve("1");
        if (Files.exists(userDir)) {
            try (Stream<Path> files = Files.list(userDir)) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test
    void rejectsDeleteWhileAnAnalysisUsingTheResumeIsStillPending(@TempDir Path tempDir) {
        AppUser user = user();
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("Backend");
        resume.setCandidateName("Arthur");
        resume.setRawText("Java");
        ReflectionTestUtils.setField(resume, "id", 5L);

        AtomicInteger deletes = new AtomicInteger();
        ResumeRepository resumeRepository = proxy(ResumeRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdAndUserId" -> Optional.of(resume);
                    case "delete" -> {
                        deletes.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "ResumeRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AppUserRepository appUserRepository = proxy(AppUserRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(user);
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AnalysisHistoryRepository analysisHistoryRepository =
                proxy(AnalysisHistoryRepository.class, (ignored, method, args) ->
                        switch (method.getName()) {
                            case "existsByResume_IdAndStatus" -> true;
                            case "toString" -> "AnalysisHistoryRepositoryTestDouble";
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
        ResumeService service = new ResumeService(
                resumeRepository,
                proxy(ResumeChunkRepository.class, (ignored, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }),
                currentUser(user),
                appUserRepository,
                new ResumeTextExtractor(new ResumeTextQualityValidator()),
                tempDir.toString(),
                null,
                SemanticEmbeddingTestSupport.service(),
                analysisHistoryRepository
        );

        BusinessException exception = assertThrows(BusinessException.class, () -> service.delete(5L));

        assertEquals("ANALYSIS_DELETE_PENDING", exception.getCode());
        // 级联删除会连带删掉 PENDING 历史，把"进行中"计数凭空清掉，所以这里必须一行都不删
        assertEquals(0, deletes.get());
    }

    @Test
    void concurrentCreatesWithOneSlotAllowAtMostOneSuccess(@TempDir Path tempDir) throws Exception {
        AtomicLong count = new AtomicLong(0);
        ReentrantLock userLock = new ReentrantLock();
        AppUser user = user();
        ResumeRepository resumeRepository = proxy(ResumeRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "countByUserId" -> count.get();
                    case "save" -> {
                        count.incrementAndGet();
                        if (userLock.isHeldByCurrentThread()) {
                            userLock.unlock();
                        }
                        yield args[0];
                    }
                    case "toString" -> "ResumeRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AppUserRepository appUserRepository = proxy(AppUserRepository.class, (ignored, method, args) -> {
            if ("findByIdForUpdate".equals(method.getName())) {
                userLock.lock();
                return Optional.of(user);
            }
            if ("toString".equals(method.getName())) {
                return "AppUserRepositoryTestDouble";
            }
            throw new UnsupportedOperationException(method.getName());
        });
        ResumeService service = new ResumeService(
                resumeRepository,
                proxy(ResumeChunkRepository.class, (ignored, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }),
                currentUser(user),
                appUserRepository,
                new ResumeTextExtractor(new ResumeTextQualityValidator()),
                tempDir.toString(),
                null,
                SemanticEmbeddingTestSupport.service(),
                null
        );
        ReflectionTestUtils.setField(service, "maxResumesPerUser", 1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger quotaRejected = new AtomicInteger();
        try {
            Future<?> first = pool.submit(() -> runCreate(service, start, userLock, successes, quotaRejected));
            Future<?> second = pool.submit(() -> runCreate(service, start, userLock, successes, quotaRejected));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, successes.get());
        assertEquals(1, quotaRejected.get());
        assertEquals(1, count.get());
    }

    @Test
    void listSummaryOmitsRawText(@TempDir Path tempDir) {
        AppUser user = user();
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("Backend");
        resume.setCandidateName("Arthur");
        resume.setRawText("secret body ".repeat(20));
        ReflectionTestUtils.setField(resume, "id", 8L);

        assertTrue(com.arthur.jdragresume.dto.resume.ResumeResponse.from(resume).rawText().contains("secret body"));
        org.junit.jupiter.api.Assertions.assertNull(
                com.arthur.jdragresume.dto.resume.ResumeResponse.summary(resume).rawText()
        );
    }

    private static void runCreate(
            ResumeService service,
            CountDownLatch start,
            ReentrantLock userLock,
            AtomicInteger successes,
            AtomicInteger quotaRejected
    ) {
        try {
            start.await(5, TimeUnit.SECONDS);
            service.create(new com.arthur.jdragresume.dto.resume.ResumeRequest(
                    "title",
                    "Arthur",
                    null,
                    null,
                    "Java backend engineer with Spring Boot and MySQL experience."
            ));
            successes.incrementAndGet();
        } catch (BusinessException exception) {
            if ("RESUME_QUOTA_EXCEEDED".equals(exception.getCode())) {
                quotaRejected.incrementAndGet();
            } else {
                throw exception;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            if (userLock.isHeldByCurrentThread()) {
                userLock.unlock();
            }
        }
    }

    private static ResumeService service(Path tempDir, long count, long storedBytes, Optional<RuntimeException> saveFailure) {
        AppUser user = user();
        ResumeRepository resumeRepository = proxy(ResumeRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "countByUserId" -> count;
                    case "sumFileSizeByUserId" -> storedBytes;
                    case "save" -> {
                        if (saveFailure.isPresent()) {
                            throw saveFailure.get();
                        }
                        yield args[0];
                    }
                    case "toString" -> "ResumeRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        AppUserRepository appUserRepository = proxy(AppUserRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(user);
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ResumeService(
                resumeRepository,
                proxy(ResumeChunkRepository.class, (ignored, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }),
                currentUser(user),
                appUserRepository,
                new ResumeTextExtractor(new ResumeTextQualityValidator()),
                tempDir.toString(),
                null,
                SemanticEmbeddingTestSupport.service(),
                null
        );
    }

    private static CurrentUserService currentUser(AppUser user) {
        return new CurrentUserService(
                proxy(AppUserRepository.class, (ignored, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                })
        ) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
    }

    private static AppUser user() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 1L);
        return user;
    }

    private static MockMultipartFile sampleFile() {
        String text = "Java backend engineer with Spring Boot, MySQL and Redis experience for RAG matching.";
        return new MockMultipartFile(
                "file",
                "resume.txt",
                "text/plain",
                text.getBytes(StandardCharsets.UTF_8)
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
