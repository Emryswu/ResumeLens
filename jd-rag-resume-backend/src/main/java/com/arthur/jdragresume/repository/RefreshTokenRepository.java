package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshToken token join fetch token.user where token.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("update RefreshToken token set token.revokedAt = :revokedAt "
            + "where token.familyId = :familyId and token.revokedAt is null")
    int revokeActiveFamily(
            @Param("familyId") String familyId,
            @Param("revokedAt") LocalDateTime revokedAt
    );

    @Modifying
    @Query("update RefreshToken token set token.revokedAt = :revokedAt, "
            + "token.replacedByHash = :reason "
            + "where token.user.id = :userId and token.revokedAt is null")
    int revokeActiveForUser(
            @Param("userId") Long userId,
            @Param("revokedAt") LocalDateTime revokedAt,
            @Param("reason") String reason
    );

    @Query("""
            select token.id from RefreshToken token
            where (token.revokedAt is null and token.expiresAt < :expiredBefore)
               or (token.expiresAt < :now and token.revokedAt is not null and token.revokedAt < :revokedBefore)
            order by token.id
            """)
    List<Long> findCleanupCandidateIds(
            @Param("expiredBefore") LocalDateTime expiredBefore,
            @Param("revokedBefore") LocalDateTime revokedBefore,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );
}
