package com.arthur.jdragresume.security;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    @NotBlank(message = "JWT secret must be configured")
    private String secret;
    @Positive(message = "access token expiration must be positive")
    @Max(value = 1440, message = "access token expiration must not exceed 1440 minutes")
    private long expirationMinutes;
    @Positive(message = "refresh token expiration must be positive")
    private long refreshExpirationDays;
    private boolean refreshCookieSecure;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    @AssertTrue(message = "JWT secret must be at least 32 bytes for HS256")
    public boolean isSecretAtLeast32Bytes() {
        return secret != null
                && !secret.isBlank()
                && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    public void setExpirationMinutes(long expirationMinutes) {
        this.expirationMinutes = expirationMinutes;
    }

    public long getRefreshExpirationDays() {
        return refreshExpirationDays;
    }

    public void setRefreshExpirationDays(long refreshExpirationDays) {
        this.refreshExpirationDays = refreshExpirationDays;
    }

    public boolean isRefreshCookieSecure() {
        return refreshCookieSecure;
    }

    public void setRefreshCookieSecure(boolean refreshCookieSecure) {
        this.refreshCookieSecure = refreshCookieSecure;
    }
}
