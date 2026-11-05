package com.arthur.jdragresume.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Startup validation; SecurityConfig invokes the same check for its refresh-cookie filter bean. */
class CookieSecurityStartupTests {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RefreshCookiePolicyConfiguration.class);

    @Test
    void httpsOriginWithInsecureCookieAbortsStartup() {
        runner.withPropertyValues("app.security.trusted-origins=https://example.com",
                "app.jwt.refresh-cookie-secure=false")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void localHttpAndSecureHttpsConfigurationsStart() {
        runner.withPropertyValues("app.security.trusted-origins=http://localhost:3000",
                "app.jwt.refresh-cookie-secure=false")
                .run(context -> assertNull(context.getStartupFailure()));
        runner.withPropertyValues("app.security.trusted-origins=https://example.com",
                "app.jwt.refresh-cookie-secure=true")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    static class RefreshCookiePolicyConfiguration {
        @Bean
        Object cookiePolicy(@Value("${app.jwt.refresh-cookie-secure:false}") boolean secure,
                @Value("${app.security.trusted-origins}") List<String> origins) {
            CookieSecurity.validateTrustedOrigins(secure, origins);
            return new Object();
        }
    }
}
