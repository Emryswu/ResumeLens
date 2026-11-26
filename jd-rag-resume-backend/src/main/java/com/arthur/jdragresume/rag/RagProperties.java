package com.arthur.jdragresume.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {
    /**
     * Character window for resume chunking. gte-multilingual-base supports 8192 tokens,
     * so ~900 CJK/Latin characters is not truncated by a 512-token limit.
     */
    private int chunkSize = 900;
    private int chunkOverlap = 120;
    private int topK = 5;
    private int maxChunks = 80;
    private int embeddingBatchSize = 8;
    /**
     * Drop chunks below this cosine similarity before Top-K.
     *
     * <p>Calibrated against experiments/threshold-sweep: on that dataset the highest
     * non-relevant chunk scored 0.690011 and the lowest gold-relevant chunk 0.751004,
     * so 0.72 sits near the middle of the only separating interval. The previous 0.55
     * fell inside the negative distribution and let every hard-negative pair through.
     * The calibration set is small and synthetic - see the experiment README before
     * reusing this number with a different embedding model or chunk size.
     */
    private double minSimilarity = 0.72;
    /** Add a small boost when JD keywords appear in a resume chunk. */
    private boolean hybridEnabled = true;
    private double keywordBoost = 0.035;
    private int maxKeywordBoosts = 3;
    /** Embed JD full text and requirements separately, then take max similarity. */
    private boolean dualQueryEnabled = true;
    private int maxLength = 8192;
    private int embeddingDimensions = 768;
    /**
     * ONNX output tensor used for CLS extraction.
     * onnx-community export names this {@code token_embeddings} (equivalent to last_hidden_state).
     */
    private String modelOutputName = "token_embeddings";
    private String poolingMode = "cls";
    private String embeddingModelId = "Alibaba-NLP/gte-multilingual-base-int8";
    private String embeddingTokenizerUri = "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/2edbf5e672aab465f9ed4c154a8b61791c082c69/tokenizer.json";
    private String embeddingModelUri = "https://huggingface.co/onnx-community/gte-multilingual-base/resolve/2edbf5e672aab465f9ed4c154a8b61791c082c69/onnx/model_int8.onnx";
    private String embeddingTokenizerSha256 = "3a56def25aa40facc030ea8b0b87f3688e4b3c39eb8b45d5702b3a1300fe2a20";
    private String embeddingModelSha256 = "ab2bd164ebd8ca9003dc49a981b611e849b5d326f504c8873ba76e07fa6c0082";
    private String queryPrefix = "Represent this sentence for searching relevant passages:";
    /** 0 means auto-size as max(2, availableProcessors / 2). */
    private int maxConcurrentEmbeddings = 0;
    private int embeddingAcquireTimeoutMs = 30_000;
    /** 0 leaves ONNX Runtime's default intra-op thread setting untouched. */
    private int onnxIntraOpThreads = 0;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }

    public void setChunkOverlap(int chunkOverlap) {
        this.chunkOverlap = chunkOverlap;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxChunks() {
        return maxChunks;
    }

    public void setMaxChunks(int maxChunks) {
        this.maxChunks = maxChunks;
    }

    public int getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(int embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }

    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = minSimilarity;
    }

    public boolean isHybridEnabled() {
        return hybridEnabled;
    }

    public void setHybridEnabled(boolean hybridEnabled) {
        this.hybridEnabled = hybridEnabled;
    }

    public double getKeywordBoost() {
        return keywordBoost;
    }

    public void setKeywordBoost(double keywordBoost) {
        this.keywordBoost = keywordBoost;
    }

    public int getMaxKeywordBoosts() {
        return maxKeywordBoosts;
    }

    public void setMaxKeywordBoosts(int maxKeywordBoosts) {
        this.maxKeywordBoosts = maxKeywordBoosts;
    }

    public boolean isDualQueryEnabled() {
        return dualQueryEnabled;
    }

    public void setDualQueryEnabled(boolean dualQueryEnabled) {
        this.dualQueryEnabled = dualQueryEnabled;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getEmbeddingDimensions() {
        return embeddingDimensions;
    }

    public void setEmbeddingDimensions(int embeddingDimensions) {
        this.embeddingDimensions = embeddingDimensions;
    }

    public String getModelOutputName() {
        return modelOutputName;
    }

    public void setModelOutputName(String modelOutputName) {
        this.modelOutputName = modelOutputName;
    }

    public String getPoolingMode() {
        return poolingMode;
    }

    public void setPoolingMode(String poolingMode) {
        this.poolingMode = poolingMode;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public String getEmbeddingTokenizerUri() {
        return embeddingTokenizerUri;
    }

    public void setEmbeddingTokenizerUri(String embeddingTokenizerUri) {
        this.embeddingTokenizerUri = embeddingTokenizerUri;
    }

    public String getEmbeddingModelUri() {
        return embeddingModelUri;
    }

    public void setEmbeddingModelUri(String embeddingModelUri) {
        this.embeddingModelUri = embeddingModelUri;
    }

    public String getEmbeddingTokenizerSha256() {
        return embeddingTokenizerSha256;
    }

    public void setEmbeddingTokenizerSha256(String embeddingTokenizerSha256) {
        this.embeddingTokenizerSha256 = embeddingTokenizerSha256;
    }

    public String getEmbeddingModelSha256() {
        return embeddingModelSha256;
    }

    public void setEmbeddingModelSha256(String embeddingModelSha256) {
        this.embeddingModelSha256 = embeddingModelSha256;
    }

    public String getQueryPrefix() {
        return queryPrefix;
    }

    public void setQueryPrefix(String queryPrefix) {
        this.queryPrefix = queryPrefix;
    }

    public int getMaxConcurrentEmbeddings() {
        return maxConcurrentEmbeddings;
    }

    public void setMaxConcurrentEmbeddings(int maxConcurrentEmbeddings) {
        this.maxConcurrentEmbeddings = maxConcurrentEmbeddings;
    }

    public int effectiveMaxConcurrentEmbeddings() {
        return maxConcurrentEmbeddings > 0
                ? maxConcurrentEmbeddings
                : Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    public int getEmbeddingAcquireTimeoutMs() {
        return embeddingAcquireTimeoutMs;
    }

    public void setEmbeddingAcquireTimeoutMs(int embeddingAcquireTimeoutMs) {
        this.embeddingAcquireTimeoutMs = embeddingAcquireTimeoutMs;
    }

    public int getOnnxIntraOpThreads() {
        return onnxIntraOpThreads;
    }

    public void setOnnxIntraOpThreads(int onnxIntraOpThreads) {
        this.onnxIntraOpThreads = onnxIntraOpThreads;
    }
}
