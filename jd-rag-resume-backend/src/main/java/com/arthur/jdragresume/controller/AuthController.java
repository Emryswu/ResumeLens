package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.auth.AuthResponse;
import com.arthur.jdragresume.dto.auth.LoginRequest;
import com.arthur.jdragresume.dto.auth.RegisterRequest;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.security.CookieSecurity;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import com.arthur.jdragresume.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "jd-rag-refresh";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final AuthService authService;
    private final JwtProperties jwtProperties;
    private final SlidingWindowRateLimiter rateLimiter;
    private final int loginMaxPerWindow;
    private final long loginWindowMs;
    private final int registerMaxPerWindow;
    private final long registerWindowMs;

    public AuthController(
            AuthService authService,
            JwtProperties jwtProperties,
            SlidingWindowRateLimiter rateLimiter,
            @Value("${app.auth.login-max-per-window:20}") int loginMaxPerWindow,
            @Value("${app.auth.login-window-minutes:15}") long loginWindowMinutes,
            @Value("${app.auth.register-max-per-window:8}") int registerMaxPerWindow,
            @Value("${app.auth.register-window-minutes:30}") long registerWindowMinutes
    ) {
        this.authService = authService;
        this.jwtProperties = jwtProperties;
        this.rateLimiter = rateLimiter;
        this.loginMaxPerWindow = Math.max(1, loginMaxPerWindow);
        this.loginWindowMs = Math.max(1L, loginWindowMinutes) * 60_000L;
        this.registerMaxPerWindow = Math.max(1, registerMaxPerWindow);
        this.registerWindowMs = Math.max(1L, registerWindowMinutes) * 60_000L;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest
    ) {
        acquire("register:" + clientKey(httpRequest), registerMaxPerWindow, registerWindowMs);
        return sessionResponse(authService.register(request), HttpStatus.CREATED, httpRequest);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        acquire(
                "login:" + clientKey(httpRequest) + ":" + request.username().trim().toLowerCase(Locale.ROOT),
                loginMaxPerWindow,
                loginWindowMs
        );
        return sessionResponse(authService.login(request), HttpStatus.OK, httpRequest);
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest
    ) {
        return sessionResponse(authService.refresh(refreshToken), HttpStatus.OK, httpRequest);
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest httpRequest
    ) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie(httpRequest).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok());
    }

    private ResponseEntity<ApiResponse<AuthResponse>> sessionResponse(
            AuthService.AuthSession session,
            HttpStatus status,
            HttpServletRequest httpRequest
    ) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken().value(), httpRequest).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.ok(session.response()));
    }

    private ResponseCookie refreshCookie(String value, HttpServletRequest httpRequest) {
        return cookieBuilder(value, httpRequest)
                .maxAge(Duration.ofDays(jwtProperties.getRefreshExpirationDays()))
                .build();
    }

    private ResponseCookie clearRefreshCookie(HttpServletRequest httpRequest) {
        return cookieBuilder("", httpRequest)
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder cookieBuilder(String value, HttpServletRequest httpRequest) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(CookieSecurity.secure(jwtProperties.isRefreshCookieSecure(), httpRequest.isSecure()))
                .sameSite("Lax")
                .path(REFRESH_COOKIE_PATH);
    }

    private void acquire(String key, int limit, long windowMs) {
        if (!rateLimiter.tryAcquire(key, limit, windowMs)) {
            throw new BusinessException("AUTH_RATE_LIMITED", "too many attempts, please retry later");
        }
    }

    private static String clientKey(HttpServletRequest request) {
        return ClientIpResolver.resolve(request);
    }
}
