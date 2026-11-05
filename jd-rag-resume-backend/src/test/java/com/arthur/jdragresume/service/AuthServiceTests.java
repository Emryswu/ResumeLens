package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.auth.LoginRequest;
import com.arthur.jdragresume.dto.auth.RegisterRequest;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.repository.RefreshTokenRepository;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceTests {
    private RepositoryState state;
    private CountingPasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        state = new RepositoryState();
        passwordEncoder = new CountingPasswordEncoder();
        AppUserRepository repository = proxy(AppUserRepository.class, (ignored, method, args) ->
                switch (method.getName()) {
                    case "existsByUsername" -> state.usernameExists || (state.knownUsername != null
                            && state.knownUsername.equalsIgnoreCase((String) args[0]));
                    case "existsByEmail" -> state.emailExists;
                    case "findByUsername" -> {
                        state.lastLookupUsername = (String) args[0];
                        yield Optional.ofNullable(state.user);
                    }
                    case "save" -> {
                        state.savedUser = (AppUser) args[0];
                        yield args[0];
                    }
                    case "toString" -> "AppUserRepositoryTestDouble";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret("0123456789abcdef0123456789abcdef");
        jwtProperties.setExpirationMinutes(15);
        jwtProperties.setRefreshExpirationDays(7);
        JwtService jwtService = new JwtService(jwtProperties, new com.fasterxml.jackson.databind.ObjectMapper());
        RefreshTokenRepository refreshTokenRepository = proxy(RefreshTokenRepository.class, (ignored, method, args) -> {
            if ("save".equals(method.getName())) {
                return args[0];
            }
            if ("toString".equals(method.getName())) {
                return "RefreshTokenRepositoryTestDouble";
            }
            throw new UnsupportedOperationException(method.getName());
        });
        authService = new AuthService(
                repository,
                passwordEncoder,
                jwtService,
                jwtProperties,
                new RefreshTokenService(refreshTokenRepository, jwtProperties, java.time.Clock.systemUTC())
        );
    }

    @Test
    void registerUsesASingleConflictCodeForUsernameOrEmail() {
        state.usernameExists = true;
        BusinessException username = assertThrows(
                BusinessException.class,
                () -> authService.register(new RegisterRequest("arthur", "a@example.com", "Arthur", "secret1"))
        );
        assertEquals("ACCOUNT_CONFLICT", username.getCode());

        state.usernameExists = false;
        state.emailExists = true;
        BusinessException email = assertThrows(
                BusinessException.class,
                () -> authService.register(new RegisterRequest("arthur", "a@example.com", "Arthur", "secret1"))
        );
        assertEquals("ACCOUNT_CONFLICT", email.getCode());
    }

    @Test
    void loginHashesPasswordEvenWhenTheUsernameIsUnknown() {
        int matchesBefore = passwordEncoder.matchesCalls.get();
        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(new LoginRequest("missing", "secret1"))
        );
        assertTrue(passwordEncoder.matchesCalls.get() > matchesBefore);
    }

    @Test
    void loginRejectsWrongPassword() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        user.setPasswordHash(passwordEncoder.encode("secret1"));
        ReflectionTestUtils.setField(user, "id", 4L);
        state.user = user;

        assertThrows(
                BadCredentialsException.class,
                () -> authService.login(new LoginRequest("arthur", "wrong-password"))
        );
    }

    @Test
    void rejectsInvalidAndReservedRegistrationNames() {
        for (String username : java.util.List.of("a", "al ice", "alice\u3000", "anonymousUser")) {
            BusinessException error = assertThrows(BusinessException.class, () ->
                    authService.register(new RegisterRequest(username, "a@example.com", "Alice", "secret1")));
            assertEquals("INVALID_USERNAME", error.getCode());
        }
    }

    @Test
    void registrationTrimsBeforeConflictLookupAndLowercasesEmail() {
        state.knownUsername = "Alice";
        BusinessException conflict = assertThrows(BusinessException.class, () ->
                authService.register(new RegisterRequest(" alice ", "a@example.com", "Alice", "secret1")));
        assertEquals("ACCOUNT_CONFLICT", conflict.getCode());

        state.knownUsername = null;
        authService.register(new RegisterRequest(" ALICE ", " PERSON@Example.COM ", "Alice", "secret1"));
        assertNotNull(state.savedUser);
        assertEquals("ALICE", state.savedUser.getUsername());
        assertEquals("person@example.com", state.savedUser.getEmail());
    }

    @Test
    void loginTrimsUsernameBeforeLookup() {
        AppUser user = new AppUser();
        user.setUsername("arthur");
        user.setPasswordHash(passwordEncoder.encode("secret1"));
        state.user = user;
        authService.login(new LoginRequest(" arthur ", "secret1"));
        assertEquals("arthur", state.lastLookupUsername);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class RepositoryState {
        private boolean usernameExists;
        private boolean emailExists;
        private String knownUsername;
        private String lastLookupUsername;
        private AppUser savedUser;
        private AppUser user;
    }

    private static final class CountingPasswordEncoder implements PasswordEncoder {
        private final BCryptPasswordEncoder delegate = new BCryptPasswordEncoder();
        private final AtomicInteger matchesCalls = new AtomicInteger();

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            matchesCalls.incrementAndGet();
            return delegate.matches(rawPassword, encodedPassword);
        }
    }
}
