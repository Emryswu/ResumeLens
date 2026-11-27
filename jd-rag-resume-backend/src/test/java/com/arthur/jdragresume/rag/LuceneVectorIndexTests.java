package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneVectorIndexTests {
    @TempDir Path tempDir;

    @Test
    void indexesFiltersAndRanksNearestResumeChunk() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(
                    chunk(0, "hash-a", new float[]{1, 0, 0}, mapper),
                    chunk(1, "hash-a", new float[]{0, 1, 0}, mapper)
            ));
            index.replace(20L, List.of(chunk(0, "hash-b", new float[]{1, 0, 0}, mapper)));

            assertTrue(index.isCurrent(10L, "hash-a", 2));
            List<LuceneVectorIndex.VectorHit> hits = index.search(10L, List.of(new float[]{0.9f, 0.1f, 0}), 2);

            assertEquals(List.of(0, 1), hits.stream().map(LuceneVectorIndex.VectorHit::chunkIndex).toList());
        }
    }

    @Test
    void replaceAndDeleteAreImmediatelyVisibleToSubsequentReads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(chunk(0, "hash-v1", new float[]{1, 0, 0}, mapper)));
            index.replace(20L, List.of(chunk(0, "hash-other", new float[]{0, 1, 0}, mapper)));

            assertTrue(index.isCurrent(10L, "hash-v1", 1));
            assertEquals(List.of(0), index.search(10L, List.of(new float[]{1, 0, 0}), 3)
                    .stream().map(LuceneVectorIndex.VectorHit::chunkIndex).toList());

            index.replace(10L, List.of(chunk(5, "hash-v2", new float[]{0, 0, 1}, mapper)));
            assertFalse(index.isCurrent(10L, "hash-v1", 1));
            assertTrue(index.isCurrent(10L, "hash-v2", 1));
            assertEquals(List.of(5), index.search(10L, List.of(new float[]{0, 0, 1}), 3)
                    .stream().map(LuceneVectorIndex.VectorHit::chunkIndex).toList());

            index.delete(10L);
            assertFalse(index.isCurrent(10L, "hash-v2", 1));
            assertTrue(index.search(10L, List.of(new float[]{0, 0, 1}), 3).isEmpty());
            assertTrue(index.isCurrent(20L, "hash-other", 1));
        }
    }

    @Test
    void releasesWriterLockWhenOpeningTheInitialSearcherFails() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        IOException readerFailure = new IOException("cannot open the initial reader");
        SearcherFactory failingFactory = new SearcherFactory() {
            @Override
            public IndexSearcher newSearcher(IndexReader reader, IndexReader previousReader) throws IOException {
                throw readerFailure;
            }
        };
        assertSame(readerFailure, assertThrows(IOException.class,
                () -> new LuceneVectorIndex(mapper, tempDir, failingFactory)));
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(chunk(0, "hash", new float[]{1, 0, 0}, mapper)));
            assertTrue(index.isCurrent(10L, "hash", 1));
        }
    }

    @Test
    void concurrentReadsAndWritesCompleteWithoutErrors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(chunk(0, "init", new float[]{1, 0, 0}, mapper)));

            int readers = 4;
            int writers = 2;
            ExecutorService pool = Executors.newFixedThreadPool(readers + writers);
            CountDownLatch start = new CountDownLatch(1);
            List<Throwable> failures = new CopyOnWriteArrayList<>();
            List<Future<?>> futures = new ArrayList<>();

            for (int r = 0; r < readers; r++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < 40; i++) {
                            index.isCurrent(10L, "hash-" + (i % 4), 1);
                            List<LuceneVectorIndex.VectorHit> hits = index.search(10L, List.of(new float[]{1, 0, 0}), 2);
                            assertTrue(hits.size() <= 2);
                        }
                    } catch (Throwable ex) {
                        failures.add(ex);
                    }
                }));
            }
            for (int w = 0; w < writers; w++) {
                int writerId = w;
                futures.add(pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < 15; i++) {
                            index.replace(10L, List.of(
                                    chunk(i, "hash-" + (i % 4), new float[]{1, 0, 0}, mapper)
                            ));
                            if (writerId == 1 && i % 5 == 0) {
                                index.delete(99L);
                            }
                        }
                    } catch (Throwable ex) {
                        failures.add(ex);
                    }
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            assertTrue(failures.isEmpty(), () -> "Unexpected concurrent errors: " + failures);
        }
    }

    @Test
    void retainOnlyPurgesOrphanDocumentsWhilePreservingActiveResumes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(chunk(0, "h10", new float[]{1, 0, 0}, mapper)));
            index.replace(20L, List.of(chunk(0, "h20", new float[]{0, 1, 0}, mapper)));
            index.replace(30L, List.of(chunk(0, "h30", new float[]{0, 0, 1}, mapper)));

            int removed = index.retainOnly(Set.of(10L, 30L));
            assertEquals(1, removed);
            assertTrue(index.isCurrent(10L, "h10", 1));
            assertFalse(index.isCurrent(20L, "h20", 1));
            assertTrue(index.search(20L, List.of(new float[]{0, 1, 0}), 3).isEmpty());
            assertTrue(index.isCurrent(30L, "h30", 1));

            // Repeated reconciliation with the same active set must not recount soft-deleted terms.
            assertEquals(0, index.retainOnly(Set.of(10L, 30L)));

            assertEquals(2, index.retainOnly(Set.of()));
            assertFalse(index.isCurrent(10L, "h10", 1));
            assertFalse(index.isCurrent(30L, "h30", 1));
        }
    }

    @Test
    void failedReplaceLeavesExistingDocumentsIntactAcrossSubsequentCommitsAndHandlesEmptySearchBounds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(10L, List.of(chunk(0, "hash-ok", new float[]{1, 0, 0}, mapper)));

            ResumeChunk malformed = new ResumeChunk();
            malformed.setChunkIndex(1);
            malformed.setSourceHash("hash-bad");
            malformed.setContent("broken");
            malformed.setEmbedding("{not-a-float-array");

            assertThrows(IOException.class, () -> index.replace(10L, List.of(
                    chunk(0, "hash-bad", new float[]{0, 1, 0}, mapper),
                    malformed
            )));

            // An unrelated commit on resume 20 must not persist a partial deletion/write from failed resume 10 replace.
            index.replace(20L, List.of(chunk(0, "hash-20", new float[]{0, 0, 1}, mapper)));
            assertTrue(index.isCurrent(10L, "hash-ok", 1));
            assertFalse(index.isCurrent(10L, "hash-bad", 1));
            assertTrue(index.search(10L, List.of(), 3).isEmpty());
            assertTrue(index.search(10L, List.of(new float[]{1, 0, 0}), 0).isEmpty());
        }
    }

    private ResumeChunk chunk(int index, String hash, float[] vector, ObjectMapper mapper) throws Exception {
        ResumeChunk chunk = new ResumeChunk();
        chunk.setChunkIndex(index);
        chunk.setSourceHash(hash);
        chunk.setContent("chunk-" + index);
        chunk.setEmbedding(mapper.writeValueAsString(vector));
        return chunk;
    }
}
