package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.ResumeChunkRepository;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumeServiceUploadTransactionTests {

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDeletesFileWrittenBeforeDatabaseCommit(@TempDir Path tempDir) throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        ResumeService service = service(tempDir);

        service.upload(file(), "Resume", "Arthur", null, null);
        assertEquals(1, regularFileCount(tempDir));

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        );

        assertEquals(0, regularFileCount(tempDir));
    }

    @Test
    void commitKeepsUploadedFile(@TempDir Path tempDir) throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        ResumeService service = service(tempDir);

        service.upload(file(), "Resume", "Arthur", null, null);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
        );

        assertEquals(1, regularFileCount(tempDir));
    }

    @Test
    void immediateDatabaseSaveFailureDeletesUploadedFile(@TempDir Path tempDir) throws Exception {
        ResumeService service = service(tempDir, true);

        // upload() 目前把 DataIntegrityViolationException 一并归入 FILE_SAVE_FAILED。
        // 这条用例锁的是"保存失败必须删掉已落盘的文件"，归因是否准确另行处理。
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.upload(file(), "Resume", "Arthur", null, null)
        );
        assertEquals("FILE_SAVE_FAILED", error.getCode());

        assertEquals(0, regularFileCount(tempDir));
    }

    private ResumeService service(Path uploadDir) {
        return service(uploadDir, false);
    }

    private ResumeService service(Path uploadDir, boolean failSave) {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        ReflectionTestUtils.setField(user, "id", 7L);
        ResumeRepository resumeRepository = proxy(
                ResumeRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        if (failSave) {
                            throw new DataIntegrityViolationException("simulated database failure");
                        }
                        yield (Resume) args[0];
                    }
                    case "countByUserId" -> 0L;
                    case "sumFileSizeByUserId" -> 0L;
                    case "toString" -> "ResumeRepositoryUploadTestDouble";
                    default -> throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
                }
        );
        AppUserRepository appUserRepository = proxy(
                AppUserRepository.class,
                (ignored, method, args) -> switch (method.getName()) {
                    case "findByIdForUpdate" -> Optional.of(user);
                    case "toString" -> "AppUserRepositoryUploadTestDouble";
                    default -> throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
                }
        );
        CurrentUserService currentUserService = new CurrentUserService(proxy(
                AppUserRepository.class,
                (ignored, method, args) -> null
        )) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
        return new ResumeService(
                resumeRepository,
                proxy(ResumeChunkRepository.class, (ignored, method, args) -> null),
                currentUserService,
                appUserRepository,
                new ResumeTextExtractor(new ResumeTextQualityValidator()),
                uploadDir.toString(),
                null,
                SemanticEmbeddingTestSupport.service(),
                null
        );
    }

    private MockMultipartFile file() {
        return new MockMultipartFile(
                "file",
                "resume.txt",
                "text/plain",
                "Java Spring Boot backend resume with MySQL, Redis, Docker and unit testing experience.".getBytes(StandardCharsets.UTF_8)
        );
    }

    private long regularFileCount(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
