package com.arthur.jdragresume.rag;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ClsOnnxEmbeddingModelTests {
    @Test
    void extractsFirstTokenAsClsAndL2Normalizes() {
        float[][][] tokenEmbeddings = new float[][][]{
                {
                        {3.0f, 0.0f, 0.0f}, // CLS
                        {9.0f, 9.0f, 9.0f}, // should be ignored by CLS pooling
                        {1.0f, 2.0f, 3.0f}
                }
        };

        List<float[]> embeddings = ClsOnnxEmbeddingModel.extractClsEmbeddings(tokenEmbeddings);

        assertEquals(1, embeddings.size());
        float[] vector = embeddings.getFirst();
        assertEquals(3, vector.length);
        assertEquals(1.0f, vector[0], 1e-5);
        assertEquals(0.0f, vector[1], 1e-5);
        assertEquals(0.0f, vector[2], 1e-5);
    }

    @Test
    void normalizesAlreadyPooledBatchMatrix() {
        float[][] matrix = new float[][]{
                {0.0f, 4.0f}
        };
        List<float[]> embeddings = ClsOnnxEmbeddingModel.extractClsEmbeddings(matrix);
        assertArrayEquals(new float[]{0.0f, 1.0f}, embeddings.getFirst(), 1e-5f);
    }

    @Test
    void meanPoolingWouldDifferFromCls() {
        // Documents that mean != CLS; regression guard for "do not mean-pool GTE".
        float[] cls = {1.0f, 0.0f};
        float[] other = {0.0f, 1.0f};
        float[] mean = {
                (cls[0] + other[0]) / 2.0f,
                (cls[1] + other[1]) / 2.0f
        };
        float[][][] tokens = new float[][][]{{cls, other}};
        float[] actualCls = ClsOnnxEmbeddingModel.extractClsEmbeddings(tokens).getFirst();

        assertTrue(Math.abs(actualCls[0] - mean[0]) > 0.1);
        assertEquals(1.0f, actualCls[0], 1e-5);
        assertEquals(0.0f, actualCls[1], 1e-5);
    }

    @Test
    void acceptsMatchingSha256() {
        ClsOnnxEmbeddingModel.verifySha256(
                "fixture",
                "pinned-model".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "8784c05c1dba20e7347b3954f1916de1ecbd38f5a2cce90063933a1ea03892d9"
        );
    }

    @Test
    void rejectsMismatchingSha256BeforeNativeLoad() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () ->
                ClsOnnxEmbeddingModel.verifySha256(
                        "fixture",
                        "tampered-model".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "8784c05c1dba20e7347b3954f1916de1ecbd38f5a2cce90063933a1ea03892d9"
                ));

        assertTrue(exception.getMessage().contains("SHA256 mismatch"));
    }

    @Test
    void destroyClosesSessionAndTokenizerEvenIfSessionCloseFails() throws Exception {
        ClsOnnxEmbeddingModel model = new ClsOnnxEmbeddingModel(
                "classpath:tok", "classpath:model", "a".repeat(64), "b".repeat(64), "last_hidden_state", Map.of(), 768);
        OrtSession session = mock(OrtSession.class);
        String minimalTokenizerJson = """
                {
                  "version": "1.0",
                  "model": {
                    "type": "WordPiece",
                    "unk_token": "[UNK]",
                    "continuing_subword_prefix": "##",
                    "max_input_chars_per_word": 100,
                    "vocab": {"[UNK]": 0, "[CLS]": 1, "[SEP]": 2, "hello": 3}
                  }
                }
                """;
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(
                new ByteArrayInputStream(minimalTokenizerJson.getBytes(StandardCharsets.UTF_8)), Map.of());
        doThrow(new OrtException("close failed")).when(session).close();
        ReflectionTestUtils.setField(model, "session", session);
        ReflectionTestUtils.setField(model, "tokenizer", tokenizer);

        assertEquals(3L, tokenizer.encode("hello").getIds()[0]);

        model.destroy();

        verify(session).close();
        assertThrows(Exception.class, () -> tokenizer.encode("hello"));
    }

    @Test
    void bulkheadLimitsConcurrentInferenceAndReleasesPermitsCleanly() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        ClsOnnxEmbeddingModel model = new ClsOnnxEmbeddingModel(
                "classpath:tok", "classpath:model", "a".repeat(64), "b".repeat(64),
                "last_hidden_state", Map.of(), 3, 2, 5_000L, 1) {
            @Override
            EmbeddingResponse runInference(List<String> texts) {
                int current = active.incrementAndGet();
                maxObserved.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
                return new EmbeddingResponse(List.of(new Embedding(new float[]{1.0f, 0.0f, 0.0f}, 0)));
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<float[]>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> model.embed("text")));
            }
            for (Future<float[]> future : futures) {
                assertEquals(3, future.get(5, TimeUnit.SECONDS).length);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(maxObserved.get() <= 2, "concurrent ONNX executions must stay <= 2, got " + maxObserved.get());
        assertEquals(2, model.availableConcurrencyPermits());
    }

    @Test
    void bulkheadTimeoutFailsFastWithoutLeakingPermits() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ClsOnnxEmbeddingModel model = new ClsOnnxEmbeddingModel(
                "classpath:tok", "classpath:model", "a".repeat(64), "b".repeat(64),
                "last_hidden_state", Map.of(), 3, 1, 50L, 1) {
            @Override
            EmbeddingResponse runInference(List<String> texts) {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return new EmbeddingResponse(List.of(new Embedding(new float[]{1.0f, 0.0f, 0.0f}, 0)));
            }
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<float[]> holder = pool.submit(() -> model.embed("first"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(0, model.availableConcurrencyPermits());

            IllegalStateException timeout = assertThrows(IllegalStateException.class,
                    () -> model.call(new EmbeddingRequest(List.of("second"), null)));
            assertTrue(timeout.getMessage().contains("bulkhead saturated"));
            assertEquals(0, model.availableConcurrencyPermits());

            release.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(1, model.availableConcurrencyPermits());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
