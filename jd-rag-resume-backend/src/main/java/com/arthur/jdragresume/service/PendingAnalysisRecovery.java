package com.arthur.jdragresume.service;

import com.arthur.jdragresume.repository.AnalysisHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Clock;

@Component
public class PendingAnalysisRecovery {
    private static final Logger log = LoggerFactory.getLogger(PendingAnalysisRecovery.class);
    private final AnalysisHistoryRepository repository;
    private final long staleMinutes;
    private final Clock clock;

    public PendingAnalysisRecovery(
            AnalysisHistoryRepository repository,
            @Value("${app.analysis.pending-timeout-minutes:10}") long staleMinutes,
            Clock clock
    ) {
        this.repository = repository;
        this.staleMinutes = Math.max(1, staleMinutes);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.analysis.recovery-interval-ms:60000}")
    @Transactional
    public void recover() {
        int recovered = repository.failStalePending(
                LocalDateTime.now(clock).minusMinutes(staleMinutes),
                "AI analysis failed: execution timed out or application restarted"
        );
        if (recovered > 0) {
            log.warn("Marked {} stale PENDING analyses as FAILED", recovered);
        }
    }
}
