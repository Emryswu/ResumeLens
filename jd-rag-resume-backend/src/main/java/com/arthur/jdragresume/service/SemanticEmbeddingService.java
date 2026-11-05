package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.common.LogSafe;
import com.arthur.jdragresume.rag.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Builds and validates the whole-document vectors used only for cheap first-stage ranking.
 * The fine-grained RAG path still owns chunk retrieval and the final match score.
 */
@Service
public class SemanticEmbeddingService {
    private static final Logger log = LoggerFactory.getLogger(SemanticEmbeddingService.class);
    private static final int FLOAT_BYTES = Float.BYTES;

    private final EmbeddingModel embeddingModel;
    private final RagProperties properties;
    private final String modelKey;

    public SemanticEmbeddingService(EmbeddingModel embeddingModel, RagProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
        this.modelKey = sha256(String.join("\n",
                properties.getEmbeddingModelId(),
                properties.getEmbeddingModelSha256(),
                properties.getEmbeddingTokenizerSha256(),
                properties.getModelOutputName(),
                properties.getPoolingMode(),
                String.valueOf(properties.getMaxLength()),
                String.valueOf(properties.getEmbeddingDimensions()),
                properties.getQueryPrefix()
        ));
    }

    public void refresh(Resume resume) {
        float[] embedding = embedTexts(List.of(resumeText(resume))).getFirst();
        resume.setSemanticEmbedding(encode(embedding));
        resume.setSemanticEmbeddingFingerprint(ContentFingerprints.resume(resume));
        resume.setSemanticEmbeddingModelKey(modelKey);
    }

    public void refresh(JobDescription jobDescription) {
        refreshJobs(List.of(jobDescription));
    }

    public void refreshJobs(List<JobDescription> jobs) {
        if (jobs.isEmpty()) {
            return;
        }
        List<float[]> embeddings = embedTexts(jobs.stream().map(this::jobText).toList());
        for (int index = 0; index < jobs.size(); index++) {
            JobDescription job = jobs.get(index);
            job.setSemanticEmbedding(encode(embeddings.get(index)));
            job.setSemanticEmbeddingFingerprint(ContentFingerprints.job(job));
            job.setSemanticEmbeddingModelKey(modelKey);
        }
    }

    public boolean isCurrent(Resume resume) {
        return cacheMatches(
                resume.getSemanticEmbedding(),
                resume.getSemanticEmbeddingFingerprint(),
                ContentFingerprints.resume(resume),
                resume.getSemanticEmbeddingModelKey()
        );
    }

    public boolean isCurrent(JobDescription job) {
        return cacheMatches(
                job.getSemanticEmbedding(),
                job.getSemanticEmbeddingFingerprint(),
                ContentFingerprints.job(job),
                job.getSemanticEmbeddingModelKey()
        );
    }

    public double similarity(Resume resume, JobDescription job) {
        if (!isCurrent(resume) || !isCurrent(job)) {
            throw new IllegalStateException("semantic embedding cache is stale");
        }
        return cosineSimilarity(decode(resume.getSemanticEmbedding()), decode(job.getSemanticEmbedding()));
    }

    String modelKey() {
        return modelKey;
    }

    private List<float[]> embedTexts(List<String> texts) {
        try {
            int batchSize = Math.max(1, properties.getEmbeddingBatchSize());
            List<float[]> embeddings = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += batchSize) {
                int end = Math.min(start + batchSize, texts.size());
                List<float[]> batch = embeddingModel.embed(texts.subList(start, end));
                if (batch.size() != end - start) {
                    throw new IllegalStateException("embedding model returned an unexpected batch size");
                }
                for (float[] embedding : batch) {
                    validateDimensions(embedding);
                    embeddings.add(embedding);
                }
            }
            return embeddings;
        } catch (RuntimeException ex) {
            log.error("Failed to build semantic-ranking embeddings {}", LogSafe.describe(ex));
            throw new BusinessException(
                    "SEMANTIC_EMBEDDING_FAILED",
                    "semantic ranking embedding is temporarily unavailable"
            );
        }
    }

    private boolean cacheMatches(byte[] embedding, String fingerprint, String expectedFingerprint, String cacheModelKey) {
        return hasValidEncoding(embedding)
                && expectedFingerprint.equals(fingerprint)
                && modelKey.equals(cacheModelKey);
    }

    private boolean hasValidEncoding(byte[] embedding) {
        if (embedding == null || embedding.length != properties.getEmbeddingDimensions() * FLOAT_BYTES) {
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(embedding).order(ByteOrder.BIG_ENDIAN);
        while (buffer.hasRemaining()) {
            if (!Float.isFinite(buffer.getFloat())) {
                return false;
            }
        }
        return true;
    }

    private void validateDimensions(float[] embedding) {
        if (embedding == null || embedding.length != properties.getEmbeddingDimensions()) {
            throw new IllegalStateException(
                    "expected " + properties.getEmbeddingDimensions() + " embedding dimensions"
            );
        }
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("embedding contains a non-finite value");
            }
        }
    }

    private String resumeText(Resume resume) {
        return "Resume title: " + nullToEmpty(resume.getTitle())
                + "\nResume:\n" + nullToEmpty(resume.getRawText());
    }

    private String jobText(JobDescription job) {
        return properties.getQueryPrefix()
                + "\nJob title: " + nullToEmpty(job.getTitle())
                + "\nJob description: " + nullToEmpty(job.getDescription())
                + "\nRequirements: " + nullToEmpty(job.getRequirements());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * FLOAT_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] decode(byte[] bytes) {
        if (bytes == null || bytes.length % FLOAT_BYTES != 0) {
            throw new IllegalArgumentException("invalid float vector encoding");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] vector = new float[bytes.length / FLOAT_BYTES];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }

    static double cosineSimilarity(float[] left, float[] right) {
        if (left.length == 0 || left.length != right.length) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
