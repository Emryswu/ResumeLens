package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.repository.JobDescriptionRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class JobApplicationService {
    public enum Status { SAVED, APPLIED, INTERVIEWING, OFFER, REJECTED, NOT_INTERESTED }
    public record Update(@NotNull Status status, @Size(max = 300) String note) { }
    public record View(Long jobId, String title, String companyName, String status, String note,
                       LocalDateTime updatedAt) { }

    private final JdbcTemplate jdbc;
    private final JobDescriptionRepository jobs;
    private final CurrentUserService currentUser;

    public JobApplicationService(JdbcTemplate jdbc, JobDescriptionRepository jobs, CurrentUserService currentUser) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public View get(long jobId) {
        Long userId = currentUser.getCurrentUser().getId();
        JobDescription job = owned(jobId, userId);
        var rows = jdbc.query("""
                SELECT a.job_description_id, j.title, j.company_name, a.status, a.note, a.updated_at
                FROM job_application a JOIN job_description j ON j.id = a.job_description_id
                WHERE a.user_id = ? AND a.job_description_id = ?
                """, JobApplicationService::read, userId, jobId);
        return rows.isEmpty() ? new View(jobId, job.getTitle(), job.getCompanyName(), "UNTRACKED", "", null) : rows.getFirst();
    }

    /** User feedback is explicitly recorded; absent entries are unknown, not implicit rejection. */
    @Transactional(readOnly = true)
    public List<View> list() {
        Long userId = currentUser.getCurrentUser().getId();
        return jdbc.query("""
                SELECT a.job_description_id, j.title, j.company_name, a.status, a.note, a.updated_at
                FROM job_application a JOIN job_description j ON j.id = a.job_description_id
                WHERE a.user_id = ? ORDER BY a.updated_at DESC, a.id DESC LIMIT 50
                """, JobApplicationService::read, userId);
    }

    @Transactional
    public View save(long jobId, Update update) {
        Long userId = currentUser.getCurrentUser().getId();
        owned(jobId, userId);
        jdbc.update("""
                INSERT INTO job_application (user_id, job_description_id, status, note, created_at, updated_at)
                VALUES (?, ?, ?, ?, NOW(6), NOW(6)) AS new_value
                ON DUPLICATE KEY UPDATE status = new_value.status,
                    note = new_value.note, updated_at = NOW(6)
                """, userId, jobId, update.status().name(), update.note() == null ? "" : update.note().trim());
        return get(jobId);
    }

    @Transactional
    public void clear(long jobId) {
        Long userId = currentUser.getCurrentUser().getId();
        owned(jobId, userId);
        jdbc.update("DELETE FROM job_application WHERE user_id = ? AND job_description_id = ?", userId, jobId);
    }

    private JobDescription owned(long jobId, Long userId) {
        return jobs.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("JobDescription", jobId));
    }

    private static View read(ResultSet rs, int row) throws SQLException {
        return new View(rs.getLong("job_description_id"), rs.getString("title"),
                rs.getString("company_name"), rs.getString("status"), rs.getString("note"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }
}
