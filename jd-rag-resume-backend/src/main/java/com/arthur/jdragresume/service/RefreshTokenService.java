package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.RefreshToken;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.RefreshTokenRepository;
import com.arthur.jdragresume.security.JwtProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int CLEANUP_BATCH_SIZE = 500;
    private static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    private static final long EXPIRED_TOKEN_RETENTION_DAYS = 1;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            JwtProperties jwtProperties,
            Clock clock
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    @Transactional
    public IssuedRefreshToken issue(AppUser user) {
        return create(user, UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public RotatedRefreshToken rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalid("refresh token is missing");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        RefreshToken current = refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(() -> invalid("refresh token is invalid"));

        if (current.getRevokedAt() != null) {
            if (PASSWORD_CHANGED.equals(current.getReplacedByHash())) {
                throw invalid("密码已修改，请重新登录");
            }
            refreshTokenRepository.revokeActiveFamily(current.getFamilyId(), now);
            throw new BusinessException("REFRESH_TOKEN_REUSED", "refresh token reuse detected");
        }
        if (!current.getExpiresAt().isAfter(now)) {
            current.setRevokedAt(now);
            refreshTokenRepository.save(current);
            throw new BusinessException("REFRESH_TOKEN_EXPIRED", "refresh token has expired");
        }

        IssuedRefreshToken replacement = create(current.getUser(), current.getFamilyId());
        current.setRevokedAt(now);
        current.setReplacedByHash(hash(replacement.value()));
        refreshTokenRepository.save(current);
        return new RotatedRefreshToken(current.getUser(), replacement);
    }

    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        LocalDateTime now = LocalDateTime.now(clock);
        refreshTokenRepository.findByTokenHashForUpdate(hash(rawToken)).ifPresent(token -> {
            if (token.getRevokedAt() == null) {
                token.setRevokedAt(now);
                refreshTokenRepository.save(token);
            }
        });
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeActiveForUser(userId, LocalDateTime.now(clock), PASSWORD_CHANGED);
    }

    @Scheduled(
            initialDelayString = "${app.jwt.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${app.jwt.cleanup-interval-ms:3600000}"
    )
    @Transactional
    public int cleanupExpiredAndRevokedTokens() {
        LocalDateTime now = LocalDateTime.now(clock);
        long revokedRetentionDays = Math.max(1, jwtProperties.getRefreshExpirationDays());
        List<Long> candidateIds = refreshTokenRepository.findCleanupCandidateIds(
                now.minusDays(EXPIRED_TOKEN_RETENTION_DAYS),
                now.minusDays(revokedRetentionDays),
                now,
                PageRequest.of(0, CLEANUP_BATCH_SIZE)
        );
        if (candidateIds.isEmpty()) {
            return 0;
        }
        refreshTokenRepository.deleteAllByIdInBatch(candidateIds);
        log.info("Deleted {} expired or retired refresh tokens", candidateIds.size());
        return candidateIds.size();
    }

    private IssuedRefreshToken create(AppUser user, String familyId) {
        byte[] bytes = new byte[48];
        SECURE_RANDOM.nextBytes(bytes);
        String rawToken = TOKEN_ENCODER.encodeToString(bytes);
        LocalDateTime expiresAt = LocalDateTime.now(clock).plusDays(jwtProperties.getRefreshExpirationDays());

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash(rawToken));
        token.setFamilyId(familyId);
        token.setExpiresAt(expiresAt);
        refreshTokenRepository.save(token);
        return new IssuedRefreshToken(rawToken, expiresAt);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("REFRESH_TOKEN_INVALID", message);
    }

    public record IssuedRefreshToken(String value, LocalDateTime expiresAt) {
    }

    public record RotatedRefreshToken(AppUser user, IssuedRefreshToken refreshToken) {
    }
}
