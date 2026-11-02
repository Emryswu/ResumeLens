package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.JobSnapshot;
import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class ContentFingerprints {
    private ContentFingerprints() {
    }

    public static String resume(Resume resume) {
        return digest(
                normalized(resume.getTitle(), false),
                normalized(resume.getRawText(), false)
        );
    }

    public static String job(JobDescription job) {
        // Delegate through the snapshot so a new fingerprint field cannot be left out of report snapshots.
        return JobSnapshot.from(job).fingerprint();
    }

    public static String job(
            String title,
            String companyName,
            String location,
            String employmentType,
            String description,
            String requirements
    ) {
        return digest(
                normalized(title, true),
                normalized(companyName, true),
                normalized(location, true),
                normalized(employmentType, true),
                normalized(description, true),
                normalized(requirements, true)
        );
    }

    public static boolean inputsMatch(AnalysisHistory history) {
        return history.getResumeFingerprint() != null
                && history.getJobFingerprint() != null
                && history.getResume() != null
                && history.getJobDescription() != null
                && history.getResumeFingerprint().equals(resume(history.getResume()))
                && history.getJobFingerprint().equals(job(history.getJobDescription()));
    }

    private static String normalized(String value, boolean caseInsensitive) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return caseInsensitive ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }

    private static String digest(String... parts) {
        String canonical = String.join("\n", parts);
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
