package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.AnalysisSubmissionRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AnalysisSubmissionRefundRepository extends JpaRepository<AnalysisSubmissionRefund, Long> {
    /** Refunds of this user's submissions made after {@code createdAt}, i.e. inside the same window as the count they offset. */
    @Query("""
            select count(r) from AnalysisSubmissionRefund r, AnalysisSubmissionLog l
            where l.id = r.submissionLogId and l.user.id = :userId and l.createdAt > :createdAt
            """)
    long countRefundedSubmissions(@Param("userId") Long userId, @Param("createdAt") LocalDateTime createdAt);

    boolean existsBySubmissionLogId(Long submissionLogId);
}
