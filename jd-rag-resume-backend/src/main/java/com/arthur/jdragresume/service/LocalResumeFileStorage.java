package com.arthur.jdragresume.service;

import com.arthur.jdragresume.common.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Local filesystem implementation of {@link ResumeFileStorage} rooted at {@code app.upload.resume-dir}.
 */
@Service
public class LocalResumeFileStorage implements ResumeFileStorage {
    private static final Logger log = LoggerFactory.getLogger(LocalResumeFileStorage.class);

    private final Path resumeUploadDir;

    public LocalResumeFileStorage(@Value("${app.upload.resume-dir:uploads/resumes}") String resumeUploadDir) {
        this.resumeUploadDir = Path.of(resumeUploadDir);
    }

    @Override
    public String store(Long userId, String extension, MultipartFile file) throws IOException {
        Path userDir = resumeUploadDir.resolve(userId.toString());
        String storedName = UUID.randomUUID() + "." + extension;
        Path storedPath = userDir.resolve(storedName);
        Files.createDirectories(userDir);
        file.transferTo(storedPath);
        return storedPath.toString();
    }

    @Override
    public InputStream open(String storedLocation) throws IOException {
        Path path = safeStoredFile(storedLocation);
        if (path == null) {
            throw new IOException("Refusing to read resume file outside upload root");
        }
        return Files.newInputStream(path);
    }

    @Override
    public void delete(String storedLocation) {
        Path storedFile = safeStoredFile(storedLocation);
        if (storedFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(storedFile);
        } catch (Exception ex) {
            log.error("Resume file cleanup failed at {}: {}", storedFile, LogSafe.describe(ex));
        }
    }

    private Path safeStoredFile(String storedFilePath) {
        if (storedFilePath == null || storedFilePath.trim().isEmpty()) {
            return null;
        }
        Path uploadRoot = resumeUploadDir.toAbsolutePath().normalize();
        Path storedFile = Path.of(storedFilePath).toAbsolutePath().normalize();
        if (!storedFile.startsWith(uploadRoot)) {
            log.warn("Refusing to access resume file outside upload root: {}", storedFile);
            return null;
        }
        return storedFile;
    }
}
