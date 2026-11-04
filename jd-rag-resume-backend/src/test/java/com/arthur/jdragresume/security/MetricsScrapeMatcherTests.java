package com.arthur.jdragresume.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsScrapeMatcherTests {
    private static final String TOKEN = "scrape-token-0123456789-0123456789-abcdef";

    @Test
    void blankMeansNoTokenAndNeverMatches() {
        for (String value : new String[] {null, "", "  "}) {
            MetricsScrapeMatcher matcher = new MetricsScrapeMatcher(value);
            assertFalse(matcher.enabled());
            assertFalse(matcher.matches(withBearer(TOKEN)));
            assertFalse(matcher.matches(withBearer("")));
        }
    }

    @Test
    void matchesOnlyTheExactConfiguredToken() {
        MetricsScrapeMatcher matcher = new MetricsScrapeMatcher(" " + TOKEN + " ");
        assertTrue(matcher.enabled());
        assertTrue(matcher.matches(withBearer(TOKEN)));
        assertFalse(matcher.matches(withBearer(TOKEN + "x")));
        assertFalse(matcher.matches(withBearer(TOKEN.substring(1))));
        assertFalse(matcher.matches(new MockHttpServletRequest("GET", "/actuator/prometheus")));
        MockHttpServletRequest basic = new MockHttpServletRequest("GET", "/actuator/prometheus");
        basic.addHeader("Authorization", "Basic " + TOKEN);
        assertFalse(matcher.matches(basic));
    }

    @Test
    void rejectsAShortTokenAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new MetricsScrapeMatcher("short"));
        assertThrows(IllegalArgumentException.class, () -> new MetricsScrapeMatcher("a".repeat(MetricsScrapeMatcher.MIN_TOKEN_CHARS - 1)));
        assertTrue(new MetricsScrapeMatcher("a".repeat(MetricsScrapeMatcher.MIN_TOKEN_CHARS)).enabled());
    }

    private static MockHttpServletRequest withBearer(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }
}
