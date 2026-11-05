package com.arthur.jdragresume.service;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.entity.RefreshToken;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.RefreshTokenRepository;
import com.arthur.jdragresume.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RefreshTokenServiceTests {
    private RepositoryState repositoryState;
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        repositoryState = new RepositoryState();
        RefreshTokenRepository repository = (RefreshTokenRepository) Proxy.newProxyInstance(
                RefreshTokenRepository.class.getClassLoader(),
                new Class<?>[]{RefreshTokenRepository.class},
                repositoryState
        );
        JwtProperties properties = new JwtProperties();
        properties.setRefreshExpirationDays(7);
        refreshTokenService = new RefreshTokenService(repository, properties);
    }

    @Test
    void storesOnlyHashAndRotatesTheRefreshToken() {
        AppUser user = new AppUser();
        user.setUsername("arthur");

        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issue(user);
        RefreshToken current = repositoryState.saved.getFirst();

        assertNotEquals(issued.value(), current.getTokenHash());
        assertEquals(64, current.getTokenHash().length());
        assertNotNull(current.getExpiresAt());

        repositoryState.saved.clear();
        repositoryState.found = Optional.of(current);
        RefreshTokenService.RotatedRefreshToken rotated = refreshTokenService.rotate(issued.value());

        assertEquals(user, rotated.user());
        assertNotEquals(issued.value(), rotated.refreshToken().value());
        assertNotNull(current.getRevokedAt());
        assertNotNull(current.getReplacedByHash());
        assertEquals(2, repositoryState.saved.size());
        assertEquals(current.getFamilyId(), repositoryState.saved.getFirst().getFamilyId());
    }

    @Test
    void reusedTokenRevokesItsActiveFamily() {
        RefreshToken reused = new RefreshToken();
        reused.setFamilyId("family-1");
        reused.setTokenHash("ignored");
        reused.setRevokedAt(LocalDateTime.now().minusSeconds(1));
        repositoryState.found = Optional.of(reused);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> refreshTokenService.rotate("already-used-token")
        );

        assertEquals("REFRESH_TOKEN_REUSED", exception.getCode());
        assertEquals("family-1", repositoryState.revokedFamilyId);
        assertNotNull(repositoryState.familyRevokedAt);
    }

    @Test
    void passwordChangeRevokedTokenIsInvalidInsteadOfReused() {
        RefreshToken revoked = new RefreshToken();
        revoked.setFamilyId("previous-session");
        revoked.setRevokedAt(LocalDateTime.now().minusSeconds(1));
        revoked.setReplacedByHash("PASSWORD_CHANGED");
        repositoryState.found = Optional.of(revoked);

        BusinessException error = assertThrows(
                BusinessException.class, () -> refreshTokenService.rotate("previous-token")
        );
        assertEquals("REFRESH_TOKEN_INVALID", error.getCode());
    }

    @Test
    void cleanupDeletesOnlyOneBoundedBatch() {
        repositoryState.cleanupCandidateIds = List.of(11L, 12L, 13L);

        int deleted = refreshTokenService.cleanupExpiredAndRevokedTokens();

        assertEquals(3, deleted);
        assertEquals(List.of(11L, 12L, 13L), repositoryState.deletedIds);
        assertEquals(500, repositoryState.cleanupPageable.getPageSize());
        assertEquals(0, repositoryState.cleanupPageable.getPageNumber());
        assertNotNull(repositoryState.expiredBefore);
        assertNotNull(repositoryState.revokedBefore);
    }

    @Test
    void cleanupSkipsDeleteWhenNoTokenIsEligible() {
        int deleted = refreshTokenService.cleanupExpiredAndRevokedTokens();

        assertEquals(0, deleted);
        assertEquals(List.of(), repositoryState.deletedIds);
    }

    private static final class RepositoryState implements InvocationHandler {
        private final List<RefreshToken> saved = new ArrayList<>();
        private Optional<RefreshToken> found = Optional.empty();
        private String revokedFamilyId;
        private LocalDateTime familyRevokedAt;
        private List<Long> cleanupCandidateIds = List.of();
        private List<Long> deletedIds = List.of();
        private LocalDateTime expiredBefore;
        private LocalDateTime revokedBefore;
        private Pageable cleanupPageable;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "save" -> {
                    RefreshToken token = (RefreshToken) args[0];
                    saved.add(token);
                    yield token;
                }
                case "findByTokenHashForUpdate" -> found;
                case "revokeActiveFamily" -> {
                    revokedFamilyId = (String) args[0];
                    familyRevokedAt = (LocalDateTime) args[1];
                    yield 1;
                }
                case "findCleanupCandidateIds" -> {
                    expiredBefore = (LocalDateTime) args[0];
                    revokedBefore = (LocalDateTime) args[1];
                    cleanupPageable = (Pageable) args[3];
                    yield cleanupCandidateIds;
                }
                case "deleteAllByIdInBatch" -> {
                    @SuppressWarnings("unchecked")
                    List<Long> ids = (List<Long>) args[0];
                    deletedIds = List.copyOf(ids);
                    yield null;
                }
                case "toString" -> "RefreshTokenRepositoryTestDouble";
                default -> throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
            };
        }
    }
}
