package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;
import com.arthur.jdragresume.repository.ResumeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LuceneVectorReconcilerTests {
    @TempDir Path tempDir;

    @Test
    void reconcilerRemovesDeletedResumesWithoutPurgingConcurrentlyIndexedNewResumes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (LuceneVectorIndex index = new LuceneVectorIndex(mapper, tempDir.toString())) {
            index.replace(101L, List.of(chunk(0, "h101", new float[]{1, 0, 0}, mapper)));
            index.replace(202L, List.of(chunk(0, "h202", new float[]{0, 1, 0}, mapper)));

            ResumeRepository repository = mock(ResumeRepository.class);
            when(repository.findExistingIds(anyCollection())).thenAnswer(inv -> {
                // Simulate a new resume #999 created and indexed right after indexedResumeIds() snapshot!
                index.replace(999L, List.of(chunk(0, "h999", new float[]{0, 0, 1}, mapper)));
                return Set.of(101L);
            });

            LuceneVectorReconciler reconciler = new LuceneVectorReconciler(repository, index);
            int removed = reconciler.reconcile();

            assertEquals(1, removed);
            assertTrue(index.isCurrent(101L, "h101", 1));
            assertFalse(index.isCurrent(202L, "h202", 1));
            assertTrue(index.isCurrent(999L, "h999", 1),
                    "concurrently created resume indexed after snapshot must never be mistaken for an orphan");
        }
    }

    @Test
    void reconcilerSwallowsRepositoryErrorsWithoutBreakingScheduler() throws Exception {
        ResumeRepository repository = mock(ResumeRepository.class);
        when(repository.findExistingIds(anyCollection())).thenThrow(new RuntimeException("db temporarily unavailable"));
        ResumeVectorStore store = mock(ResumeVectorStore.class);
        when(store.indexedResumeIds()).thenReturn(Set.of(1L));

        LuceneVectorReconciler reconciler = new LuceneVectorReconciler(repository, store);
        assertEquals(0, reconciler.reconcile());
    }

    private static ResumeChunk chunk(int index, String hash, float[] vector, ObjectMapper mapper) throws Exception {
        ResumeChunk chunk = new ResumeChunk();
        chunk.setChunkIndex(index);
        chunk.setSourceHash(hash);
        chunk.setContent("chunk-" + index);
        chunk.setEmbedding(mapper.writeValueAsString(vector));
        return chunk;
    }
}
