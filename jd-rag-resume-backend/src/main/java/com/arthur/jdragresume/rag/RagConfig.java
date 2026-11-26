package com.arthur.jdragresume.rag;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RagConfig {
    @Bean
    public EmbeddingModel embeddingModel(RagProperties properties) {
        // Official gte-multilingual-base uses CLS: last_hidden_state[:, 0] + L2 normalize.
        // Spring AI TransformersEmbeddingModel always mean-pools, which is the wrong pooling
        // for this model — so we use a dedicated CLS ONNX wrapper.
        return new ClsOnnxEmbeddingModel(
                properties.getEmbeddingTokenizerUri(),
                properties.getEmbeddingModelUri(),
                properties.getEmbeddingTokenizerSha256(),
                properties.getEmbeddingModelSha256(),
                properties.getModelOutputName(),
                Map.of(
                        "padding", "true",
                        "truncation", "true",
                        // Model card: 8192 tokens. onnx-community tokenizer.json may declare
                        // model_max_length=512; override so DJL does not clamp wrongly.
                        "modelMaxLength", String.valueOf(properties.getMaxLength()),
                        "maxLength", String.valueOf(properties.getMaxLength())
                ),
                properties.getEmbeddingDimensions(),
                properties.effectiveMaxConcurrentEmbeddings(),
                properties.getEmbeddingAcquireTimeoutMs(),
                properties.getOnnxIntraOpThreads()
        );
    }
}
