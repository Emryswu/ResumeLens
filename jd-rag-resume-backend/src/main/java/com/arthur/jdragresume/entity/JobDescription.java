package com.arthur.jdragresume.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_description")
public class JobDescription extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 语义向量刷新会在长事务里回写整行，乐观锁保证并发编辑不会被静默覆盖。
    @Version
    @Column(nullable = false)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 120)
    private String companyName;

    @Column(length = 80)
    private String location;

    @Column(length = 60)
    private String employmentType;

    /** Work-experience filter used by the latest listing collection; null means unknown. */
    @Column(length = 10)
    private String collectionExperience;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String description;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String requirements;

    @Column(length = 32)
    private String sourcePlatform;

    @Column(length = 2048)
    private String sourceUrl;

    @Column(length = 160)
    private String sourceJobId;

    @Column(length = 64)
    private String contentFingerprint;

    @Lob
    @Column(name = "semantic_embedding", columnDefinition = "BLOB")
    private byte[] semanticEmbedding;

    @Column(name = "semantic_embedding_fingerprint", length = 64)
    private String semanticEmbeddingFingerprint;

    @Column(name = "semantic_embedding_model_key", length = 64)
    private String semanticEmbeddingModelKey;

    private LocalDateTime lastSeenAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_depth", nullable = false, length = 16)
    private CaptureDepth captureDepth = CaptureDepth.FULL;

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getEmploymentType() {
        return employmentType;
    }

    public void setEmploymentType(String employmentType) {
        this.employmentType = employmentType;
    }

    public String getCollectionExperience() { return collectionExperience; }

    public void setCollectionExperience(String collectionExperience) { this.collectionExperience = collectionExperience; }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRequirements() {
        return requirements;
    }

    public void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public String getSourcePlatform() {
        return sourcePlatform;
    }

    public void setSourcePlatform(String sourcePlatform) {
        this.sourcePlatform = sourcePlatform;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getSourceJobId() {
        return sourceJobId;
    }

    public void setSourceJobId(String sourceJobId) {
        this.sourceJobId = sourceJobId;
    }

    public String getContentFingerprint() {
        return contentFingerprint;
    }

    public void setContentFingerprint(String contentFingerprint) {
        this.contentFingerprint = contentFingerprint;
    }

    public byte[] getSemanticEmbedding() {
        return semanticEmbedding;
    }

    public void setSemanticEmbedding(byte[] semanticEmbedding) {
        this.semanticEmbedding = semanticEmbedding;
    }

    public String getSemanticEmbeddingFingerprint() {
        return semanticEmbeddingFingerprint;
    }

    public void setSemanticEmbeddingFingerprint(String semanticEmbeddingFingerprint) {
        this.semanticEmbeddingFingerprint = semanticEmbeddingFingerprint;
    }

    public String getSemanticEmbeddingModelKey() {
        return semanticEmbeddingModelKey;
    }

    public void setSemanticEmbeddingModelKey(String semanticEmbeddingModelKey) {
        this.semanticEmbeddingModelKey = semanticEmbeddingModelKey;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public CaptureDepth getCaptureDepth() {
        return captureDepth;
    }

    public void setCaptureDepth(CaptureDepth captureDepth) {
        this.captureDepth = captureDepth;
    }
}
