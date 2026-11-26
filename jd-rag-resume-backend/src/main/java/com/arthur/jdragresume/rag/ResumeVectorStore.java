package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.entity.ResumeChunk;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Abstraction for per-instance or shared resume chunk vector storage and KNN retrieval.
 */
public interface ResumeVectorStore {
    boolean isCurrent(Long resumeId, String sourceHash, int expectedChunks) throws IOException;

    void replace(Long resumeId, List<ResumeChunk> chunks) throws IOException;

    void delete(Long resumeId) throws IOException;

    List<LuceneVectorIndex.VectorHit> search(Long resumeId, List<float[]> queries, int limit) throws IOException;

    /**
     * Returns the set of distinct resume IDs currently holding live documents in the store.
     */
    default Set<Long> indexedResumeIds() throws IOException {
        return Set.of();
    }

    /**
     * Deletes all documents belonging to the specified {@code resumeIds} in one batch.
     *
     * @return number of resume IDs targeted for deletion
     */
    default int deleteAll(Set<Long> resumeIds) throws IOException {
        if (resumeIds == null || resumeIds.isEmpty()) {
            return 0;
        }
        for (Long id : resumeIds) {
            delete(id);
        }
        return resumeIds.size();
    }

    /**
     * Removes any indexed documents whose {@code resumeId} is absent from {@code activeResumeIds}.
     *
     * @return number of orphaned resume IDs purged from the index
     */
    default int retainOnly(Set<Long> activeResumeIds) throws IOException {
        return 0;
    }
}
