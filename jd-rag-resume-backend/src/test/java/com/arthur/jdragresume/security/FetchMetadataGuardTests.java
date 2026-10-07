package com.arthur.jdragresume.security;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FetchMetadataGuardTests {
    private final FetchMetadataGuard guard = new FetchMetadataGuard(List.of("http://localhost:3000"), new ObjectMapper());

    @Test
    void fetchMetadataDecidesWhenPresentRegardlessOfOrigin() {
        assertDoesNotThrow(() -> guard.requireSameOrigin("same-origin", "https://evil.example.com"));
        assertDoesNotThrow(() -> guard.requireSameOrigin("none", null));
        assertThrows(BusinessException.class, () -> guard.requireSameOrigin("same-site", "http://localhost:3000"));
        assertThrows(BusinessException.class, () -> guard.requireSameOrigin("cross-site", null));
    }

    @Test
    void withoutFetchMetadataTheFullOriginMustBeTrusted() {
        assertDoesNotThrow(() -> guard.requireSameOrigin(null, "http://localhost:3000"));
        // 大小写按 origin 规范化后比较
        assertDoesNotThrow(() -> guard.requireSameOrigin(null, "HTTP://LOCALHOST:3000"));

        for (String origin : List.of(
                "https://localhost:3000",   // 只差协议
                "http://localhost:8081",    // 只差端口
                "http://localhost:3000.evil.example.com",
                "null",
                "not a url")) {
            assertThrows(BusinessException.class, () -> guard.requireSameOrigin(null, origin), origin);
        }
    }

    @Test
    void noFetchMetadataAndNoOriginIsANonBrowserClient() {
        assertDoesNotThrow(() -> guard.requireSameOrigin(null, null));
        assertDoesNotThrow(() -> guard.requireSameOrigin(" ", ""));
    }

    @Test
    void defaultPortsNormaliseOnBothSides() {
        FetchMetadataGuard https = new FetchMetadataGuard(List.of("https://app.example.com:443"), new ObjectMapper());
        assertDoesNotThrow(() -> https.requireSameOrigin(null, "https://app.example.com"));
        assertEquals("http://example.com", FetchMetadataGuard.normalise("http://Example.com:80"));
    }
}
