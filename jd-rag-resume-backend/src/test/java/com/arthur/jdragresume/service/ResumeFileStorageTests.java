package com.arthur.jdragresume.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeFileStorageTests {
    @TempDir Path tempDir;

    @Test
    void storesReadsAndDeletesFilesWithinUploadRoot() throws Exception {
        Path uploadRoot = tempDir.resolve("uploads");
        LocalResumeFileStorage storage = new LocalResumeFileStorage(uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file", "cv.txt", "text/plain", "hello world".getBytes(StandardCharsets.UTF_8));

        String location = storage.store(42L, "txt", file);
        assertTrue(Path.of(location).toAbsolutePath().normalize().startsWith(uploadRoot.toAbsolutePath().normalize()));
        assertTrue(Files.exists(Path.of(location)));

        try (InputStream in = storage.open(location)) {
            assertEquals("hello world", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        storage.delete(location);
        assertFalse(Files.exists(Path.of(location)));
    }

    @Test
    void refusesToOpenOrDeleteFilesOutsideUploadRoot() throws Exception {
        Path uploadRoot = tempDir.resolve("uploads");
        Path outside = tempDir.resolve("secret.txt");
        Files.writeString(outside, "sensitive");
        LocalResumeFileStorage storage = new LocalResumeFileStorage(uploadRoot.toString());

        assertThrows(IOException.class, () -> storage.open(outside.toString()));
        storage.delete(outside.toString());
        assertTrue(Files.exists(outside), "file outside upload root must never be deleted");
    }
}
