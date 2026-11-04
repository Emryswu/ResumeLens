package com.arthur.jdragresume.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Matches a metrics scrape that presents the operator's scrape token as {@code Authorization: Bearer}. The
 * endpoint is permitted only through this matcher, so with no token configured it answers to nobody, and a
 * user's JWT never opens it: instance-wide counters are operator data, not user data. Health stays on the same
 * port as the API because the UI badge and the compose healthcheck read it there.
 */
public final class MetricsScrapeMatcher implements RequestMatcher {
    /** Same floor as the JWT secret: a short token could be brute-forced against a public port. */
    public static final int MIN_TOKEN_CHARS = 32;

    private final byte[] token;

    /** {@code configured} is the raw property value; blank means no scrape token, which never matches. */
    public MetricsScrapeMatcher(String configured) {
        String value = configured == null ? "" : configured.strip();
        if (!value.isEmpty() && value.length() < MIN_TOKEN_CHARS) {
            throw new IllegalArgumentException("app.metrics.scrape-token must be at least " + MIN_TOKEN_CHARS + " characters");
        }
        this.token = value.isEmpty() ? null : value.getBytes(StandardCharsets.UTF_8);
    }

    public boolean enabled() {
        return token != null;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
        if (!enabled()) return false;
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        byte[] presented = header.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(token, presented);
    }
}
