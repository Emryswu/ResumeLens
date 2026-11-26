package com.arthur.jdragresume.rag;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.transformers.ResourceCacheService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ONNX embedding model that uses CLS / first-token pooling.
 * <p>
 * Spring AI's {@code TransformersEmbeddingModel} always mean-pools token embeddings.
 * Official GTE multilingual usage takes {@code last_hidden_state[:, 0]} (CLS) and L2-normalizes.
 */
public class ClsOnnxEmbeddingModel implements EmbeddingModel, InitializingBean, DisposableBean {
    private final String tokenizerUri;
    private final String modelUri;
    private final String tokenizerSha256;
    private final String modelSha256;
    private final String modelOutputName;
    private final Map<String, String> tokenizerOptions;
    private final int embeddingDimensions;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment environment;
    private OrtSession session;
    private Set<String> modelInputNames;
    private ResourceCacheService cacheService;

    public ClsOnnxEmbeddingModel(
            String tokenizerUri,
            String modelUri,
            String tokenizerSha256,
            String modelSha256,
            String modelOutputName,
            Map<String, String> tokenizerOptions,
            int embeddingDimensions
    ) {
        this.tokenizerUri = tokenizerUri;
        this.modelUri = modelUri;
        this.tokenizerSha256 = tokenizerSha256;
        this.modelSha256 = modelSha256;
        this.modelOutputName = modelOutputName;
        this.tokenizerOptions = tokenizerOptions == null ? Map.of() : Map.copyOf(tokenizerOptions);
        this.embeddingDimensions = embeddingDimensions;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.cacheService = new ResourceCacheService();
        Resource tokenizerResource = cacheService.getCachedResource(
                new DefaultResourceLoader().getResource(tokenizerUri)
        );
        Resource modelResource = cacheService.getCachedResource(
                new DefaultResourceLoader().getResource(modelUri)
        );

        byte[] tokenizerBytes = tokenizerResource.getContentAsByteArray();
        verifySha256("tokenizer", tokenizerBytes, tokenizerSha256);
        try (InputStream tokenizerStream = new ByteArrayInputStream(tokenizerBytes)) {
            this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerStream, tokenizerOptions);
        }

        byte[] modelBytes = modelResource.getContentAsByteArray();
        verifySha256("ONNX model", modelBytes, modelSha256);
        this.environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions()) {
            this.session = environment.createSession(modelBytes, sessionOptions);
        }
        this.modelInputNames = session.getInputNames();
        Set<String> outputs = session.getOutputNames();
        Assert.isTrue(
                outputs.contains(modelOutputName),
                "ONNX outputs " + outputs + " do not contain expected output: " + modelOutputName
        );
    }

    static void verifySha256(String resourceName, byte[] content, String expectedSha256) {
        Assert.hasText(expectedSha256, resourceName + " SHA256 must be configured");
        Assert.isTrue(expectedSha256.matches("(?i)[0-9a-f]{64}"),
                resourceName + " SHA256 must contain exactly 64 hexadecimal characters");
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException(resourceName + " SHA256 mismatch: expected "
                        + expectedSha256.toLowerCase() + ", got " + actual);
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public void destroy() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException ignored) {
            // best effort
        }
        try {
            if (tokenizer != null) {
                tokenizer.close();
            }
        } catch (Exception ignored) {
            // best effort
        }
    }

    @Override
    public float[] embed(String text) {
        return embed(List.of(text)).getFirst();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return call(new EmbeddingRequest(texts, null)).getResults().stream()
                .map(Embedding::getOutput)
                .toList();
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> texts = request.getInstructions();
        if (texts == null || texts.isEmpty()) {
            return new EmbeddingResponse(List.of());
        }
        try {
            Encoding[] encodings = tokenizer.batchEncode(texts);
            long[][] inputIds = new long[encodings.length][];
            long[][] attentionMask = new long[encodings.length][];
            long[][] tokenTypeIds = new long[encodings.length][];
            for (int i = 0; i < encodings.length; i++) {
                inputIds[i] = encodings[i].getIds();
                attentionMask[i] = encodings[i].getAttentionMask();
                tokenTypeIds[i] = encodings[i].getTypeIds();
            }

            try (
                    OnnxTensor inputIdsTensor = OnnxTensor.createTensor(environment, inputIds);
                    OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(environment, attentionMask);
                    OnnxTensor tokenTypeIdsTensor = OnnxTensor.createTensor(environment, tokenTypeIds)
            ) {
                Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
                feeds.put("input_ids", inputIdsTensor);
                feeds.put("attention_mask", attentionMaskTensor);
                feeds.put("token_type_ids", tokenTypeIdsTensor);
                feeds = feeds.entrySet().stream()
                        .filter(entry -> modelInputNames.contains(entry.getKey()))
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> left,
                                LinkedHashMap::new
                        ));

                try (OrtSession.Result results = session.run(feeds)) {
                    OnnxValue hidden = results.get(modelOutputName)
                            .orElseThrow(() -> new IllegalStateException("missing ONNX output: " + modelOutputName));
                    Object value = hidden.getValue();
                    List<float[]> embeddings = extractClsEmbeddings(value);
                    List<Embedding> data = new ArrayList<>(embeddings.size());
                    for (int i = 0; i < embeddings.size(); i++) {
                        data.add(new Embedding(embeddings.get(i), i));
                    }
                    return new EmbeddingResponse(data);
                }
            }
        } catch (OrtException ex) {
            throw new IllegalStateException("ONNX CLS embedding failed", ex);
        }
    }

    @Override
    public int dimensions() {
        return embeddingDimensions;
    }

    static List<float[]> extractClsEmbeddings(Object onnxValue) {
        if (onnxValue instanceof float[][][] tokenEmbeddings) {
            // [batch, seq, dim] -> take first token (CLS)
            List<float[]> result = new ArrayList<>(tokenEmbeddings.length);
            for (float[][] sequence : tokenEmbeddings) {
                if (sequence.length == 0) {
                    result.add(new float[0]);
                    continue;
                }
                result.add(l2Normalize(truncateOrCopy(sequence[0])));
            }
            return result;
        }
        if (onnxValue instanceof float[][] matrix) {
            // Already [batch, dim]
            List<float[]> result = new ArrayList<>(matrix.length);
            for (float[] row : matrix) {
                result.add(l2Normalize(truncateOrCopy(row)));
            }
            return result;
        }
        throw new IllegalStateException("Unsupported ONNX embedding tensor type: " + onnxValue.getClass().getName());
    }

    static float[] l2Normalize(float[] vector) {
        double sumSquares = 0.0;
        for (float value : vector) {
            sumSquares += (double) value * value;
        }
        if (sumSquares <= 0.0) {
            return vector;
        }
        float scale = (float) (1.0 / Math.sqrt(sumSquares));
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] * scale;
        }
        return normalized;
    }

    private static float[] truncateOrCopy(float[] source) {
        return source.clone();
    }
}
