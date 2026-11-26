package com.arthur.jdragresume.rag;

import com.arthur.jdragresume.common.LogSafe;
import com.arthur.jdragresume.repository.ResumeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reconciles the local Lucene NRT index against MySQL so resumes deleted by other
 * application instances do not linger as orphaned documents.
 * <p>
 * Uses a two-phase set-difference protocol ({@code indexedIds} snapshot first, then
 * {@code findExistingIds(indexedIds)} check) so resumes created and indexed concurrently
 * after the snapshot are never mistaken for orphans.
 */
@Component
public class LuceneVectorReconciler {
    private static final Logger log = LoggerFactory.getLogger(LuceneVectorReconciler.class);
    private static final int CHECK_BATCH_SIZE = 500;

    private final ResumeRepository resumeRepository;
    private final ResumeVectorStore vectorStore;

    public LuceneVectorReconciler(ResumeRepository resumeRepository, ResumeVectorStore vectorStore) {
        this.resumeRepository = resumeRepository;
        this.vectorStore = vectorStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${app.rag.lucene-reconcile-interval-ms:300000}")
    public void reconcilePeriodically() {
        reconcile();
    }

    public int reconcile() {
        try {
            Set<Long> indexedIds = vectorStore.indexedResumeIds();
            if (indexedIds.isEmpty()) {
                return 0;
            }
            List<Long> candidates = new ArrayList<>(indexedIds);
            Set<Long> aliveIds = new HashSet<>();
            for (int start = 0; start < candidates.size(); start += CHECK_BATCH_SIZE) {
                int end = Math.min(start + CHECK_BATCH_SIZE, candidates.size());
                aliveIds.addAll(resumeRepository.findExistingIds(candidates.subList(start, end)));
            }
            Set<Long> orphanIds = new HashSet<>(indexedIds);
            orphanIds.removeAll(aliveIds);
            if (orphanIds.isEmpty()) {
                return 0;
            }
            int removed = vectorStore.deleteAll(orphanIds);
            if (removed > 0) {
                log.info("Reconciled local vector index and purged {} orphaned resume entries", removed);
            }
            return removed;
        } catch (Exception ex) {
            log.warn("Local vector index reconciliation failed: {}", LogSafe.describe(ex));
            return 0;
        }
    }
}
