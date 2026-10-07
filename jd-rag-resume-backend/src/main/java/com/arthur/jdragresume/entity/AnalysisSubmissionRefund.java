package com.arthur.jdragresume.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次提交的限流次数被退还的事实。和 {@link AnalysisSubmissionLog} 一样只追加：
 * 提交日志本身从不修改，限流计数 = 窗口内的提交 − 其中已退还的提交。
 */
@Entity
@Table(name = "analysis_submission_refund")
public class AnalysisSubmissionRefund {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "submission_log_id", nullable = false, updatable = false, unique = true)
    private Long submissionLogId;

    @Column(nullable = false, updatable = false, length = 64)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSubmissionLogId() {
        return submissionLogId;
    }

    public void setSubmissionLogId(Long submissionLogId) {
        this.submissionLogId = submissionLogId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
