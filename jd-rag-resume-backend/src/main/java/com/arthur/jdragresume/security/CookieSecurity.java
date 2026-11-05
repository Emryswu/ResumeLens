package com.arthur.jdragresume.security;

import java.util.List;

public final class CookieSecurity {
    private CookieSecurity() {
    }

    /**
     * Local HTTP keeps working when the property is false. Direct HTTPS to the
     * API still gets the Secure flag even if the property was left at the
     * development default.
     */
    public static boolean secure(boolean configuredSecure, boolean requestSecure) {
        return configuredSecure || requestSecure;
    }

    /** Reverse proxies can terminate HTTPS while the backend sees an HTTP connection. */
    public static void validateTrustedOrigins(boolean configuredSecure, List<String> trustedOrigins) {
        boolean publicHttps = trustedOrigins.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).anyMatch(value -> value.regionMatches(true, 0, "https://", 0, 8));
        if (publicHttps && !configuredSecure) {
            throw new IllegalStateException(
                    "https trusted-origins require app.jwt.refresh-cookie-secure=true");
        }
    }
}
