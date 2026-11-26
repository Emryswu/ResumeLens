package com.arthur.jdragresume.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Storage strategy for uploaded resume files (local filesystem by default; replaceable with shared/object storage).
 */
public interface ResumeFileStorage {
    String store(Long userId, String extension, MultipartFile file) throws IOException;

    InputStream open(String storedLocation) throws IOException;

    void delete(String storedLocation);
}
