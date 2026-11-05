package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.RefreshTokenRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AppUserServiceTests {
    private AppUser user;
    private AppUserService service;
    private int saved;
    private int revoked;
    private int issued;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 7L);
        user.setUsername("arthur");
        user.setEmail("old@example.com");
        user.setDisplayName("Arthur");
        user.setPasswordHash("encoded-old-password");

        AppUserRepository repository = (AppUserRepository) Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(), new Class<?>[]{AppUserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "save" -> {
                        saved++;
                        yield args[0];
                    }
                    case "findByUsername" -> "arthur".equalsIgnoreCase((String) args[0])
                            ? Optional.of(user) : Optional.empty();
                    case "existsByEmail" -> false;
                    default -> throw new AssertionError(method.getName());
                });
        RefreshTokenRepository tokenRepository = (RefreshTokenRepository) Proxy.newProxyInstance(
                RefreshTokenRepository.class.getClassLoader(), new Class<?>[]{RefreshTokenRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "revokeActiveForUser" -> {
                        assertEquals(7L, args[0]);
                        assertEquals("PASSWORD_CHANGED", args[2]);
                        revoked++;
                        yield 1;
                    }
                    case "save" -> {
                        issued++;
                        yield args[0];
                    }
                    default -> throw new AssertionError(method.getName());
                });
        PasswordEncoder encoder = new PasswordEncoder() {
            @Override
            public String encode(CharSequence rawPassword) {
                return "encoded-" + rawPassword;
            }
            @Override
            public boolean matches(CharSequence rawPassword, String encodedPassword) {
                return encode(rawPassword).equals(encodedPassword);
            }
        };
        CurrentUserService currentUser = new CurrentUserService(repository) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
        JwtProperties properties = new JwtProperties();
        properties.setRefreshExpirationDays(7);
        RefreshTokenService tokens = new RefreshTokenService(tokenRepository, properties,
                Clock.fixed(Instant.parse("2026-11-05T08:00:00Z"), ZoneOffset.UTC));
        service = new AppUserService(repository, encoder, currentUser, tokens, new SlidingWindowRateLimiter(() -> 1_000_000L));
    }

    @Test
    void changingEmailRequiresCorrectCurrentPassword() {
        for (String current : new String[]{null, "", "incorrect"}) {
            BusinessException error = assertThrows(BusinessException.class, () ->
                    service.updateMe(new UserRequest("arthur", "new@example.com", "New", null, current), "192.0.2.10"));
            assertEquals("CURRENT_PASSWORD_INVALID", error.getCode());
        }
        assertEquals("old@example.com", user.getEmail());
        assertEquals(0, saved);
    }

    @Test
    void changingPasswordRequiresCorrectCurrentPassword() {
        for (String current : new String[]{null, "", "incorrect"}) {
            BusinessException error = assertThrows(BusinessException.class, () ->
                    service.updateMe(new UserRequest("arthur", "old@example.com", "New", "new-secret", current), "192.0.2.10"));
            assertEquals("CURRENT_PASSWORD_INVALID", error.getCode());
        }
        assertEquals("encoded-old-password", user.getPasswordHash());
        assertEquals(0, revoked);
        assertEquals(0, issued);
    }

    @Test
    void successfulPasswordChangeRevokesExistingTokensAndIssuesNewSession() {
        var updated = service.updateMe(new UserRequest(
                "ARTHUR", "old@example.com", "New", "new-secret", "old-password"), "192.0.2.10");
        assertEquals("encoded-new-secret", user.getPasswordHash());
        assertEquals(1, saved);
        assertEquals(1, revoked);
        assertEquals(1, issued);
        assertNotNull(updated.refreshToken());
        assertEquals("New", updated.user().displayName());
    }

    @Test
    void emailUpdateNormalizesAndKeepsRefreshSession() {
        var updated = service.updateMe(new UserRequest(
                "ARTHUR", " New@Example.COM ", "New", null, "old-password"), "192.0.2.10");
        assertEquals("new@example.com", user.getEmail());
        assertEquals("encoded-old-password", user.getPasswordHash());
        assertNull(updated.refreshToken());
        assertEquals(0, revoked);
    }

    @Test
    void profileOnlyUpdateNeedsNoPasswordAndCaseDifferenceIsNotRename() {
        service.updateMe(new UserRequest("ARTHUR", "old@example.com", "New", null, null), "192.0.2.10");
        assertEquals("arthur", user.getUsername());
        assertEquals("New", user.getDisplayName());
    }

    @Test
    void actualUsernameRenameIsRejected() {
        BusinessException error = assertThrows(BusinessException.class, () ->
                service.updateMe(new UserRequest("arthur-renamed", "old@example.com", "New", null, null), "192.0.2.10"));
        assertEquals("USERNAME_IMMUTABLE", error.getCode());
        assertEquals(0, saved);
    }
}
