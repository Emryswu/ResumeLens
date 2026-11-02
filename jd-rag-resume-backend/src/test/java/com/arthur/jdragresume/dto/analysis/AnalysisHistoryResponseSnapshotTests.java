package com.arthur.jdragresume.dto.analysis;

import com.arthur.jdragresume.entity.AnalysisHistory;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.service.ContentFingerprints;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisHistoryResponseSnapshotTests {
    private final AnalysisHistory history = history();

    @Test
    void archivedReportUsesItsOwnJobSnapshotEvenAfterTheJobIsEdited() {
        JobDescription job = history.getJobDescription();
        job.setTitle("Python 后端");
        job.setCompanyName("新公司");
        job.setRequirements("Python, Django");

        AnalysisHistoryResponse response = AnalysisHistoryResponse.from(history);

        assertEquals("Java 后端", response.jobTitle());
        assertEquals("旧公司", response.jobSnapshot().companyName());
        assertEquals("Java, Spring Boot", response.jobSnapshot().requirements());
        assertTrue(response.jobInputChanged());
    }

    @Test
    void oldReportWithoutSnapshotExplicitlyMarksChangedJob() {
        history.setJobSnapshot(null);
        history.getJobDescription().setRequirements("Python");

        AnalysisHistoryResponse response = AnalysisHistoryResponse.from(history);

        assertNull(response.jobSnapshot());
        assertTrue(response.jobInputChanged());
    }

    @Test
    void oldReportWithUnchangedInputGetsEquivalentJdInTheSameResponse() {
        history.setJobSnapshot(null);

        AnalysisHistoryResponse response = AnalysisHistoryResponse.from(history);

        assertEquals("Java, Spring Boot", response.jobSnapshot().requirements());
        assertFalse(response.jobInputChanged());
    }

    @Test
    void oldReportWithNoFingerprintIsNotAssumedCurrent() {
        history.setJobSnapshot(null);
        history.setJobFingerprint(null);

        assertTrue(AnalysisHistoryResponse.from(history).jobInputChanged());
    }

    private static AnalysisHistory history() {
        AppUser user = new AppUser();
        user.setUsername("test");
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setTitle("resume");
        JobDescription job = new JobDescription();
        job.setUser(user);
        job.setTitle("Java 后端");
        job.setCompanyName("旧公司");
        job.setDescription("服务开发");
        job.setRequirements("Java, Spring Boot");
        AnalysisHistory history = new AnalysisHistory();
        history.setUser(user);
        history.setResume(resume);
        history.setJobDescription(job);
        history.setJobFingerprint(ContentFingerprints.job(job));
        history.setJobSnapshot(JobSnapshot.write(job));
        return history;
    }
}
