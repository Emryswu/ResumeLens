package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import com.arthur.jdragresume.service.AuthService;
import com.arthur.jdragresume.service.RefreshTokenService.IssuedRefreshToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthRateLimitTests {
    private static final String LOGIN_BODY = """
            {"username":"victim","password":"secret123"}
            """;
    private static final String REGISTER_BODY = """
            {"username":"newuser","password":"secret123","email":"n@example.com","displayName":"New"}
            """;
    private final AuthService authService = mock(AuthService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var session = new AuthService.AuthSession(null, new IssuedRefreshToken("token", null));
        when(authService.login(any())).thenReturn(session);
        when(authService.register(any())).thenReturn(session);
        when(authService.refresh(any())).thenReturn(session);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, new JwtProperties(),
                        new SlidingWindowRateLimiter(() -> 1000L, 6), new ClientIpResolver(""), 3, 3, 2, 15, 8, 30))
                .setControllerAdvice(new GlobalExceptionHandler(), new JsonBodyLimit()).build();
    }

    private static MockHttpServletRequestBuilder request(String path) {
        return post(path).contentType("application/json").content(LOGIN_BODY);
    }

    @Test
    void rotatingUsernamesHitsIpLimitBeforeAuthentication() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(request("/api/auth/login").content(LOGIN_BODY.replace("victim", "name" + i))
                            .header("X-Forwarded-For", "198.51.100." + i))
                    .andExpect(status().is(i < 3 ? 200 : 429));
        }
        verify(authService, times(3)).login(any());
    }

    @Test
    void eachIpStillHitsItsOwnUsernameLimit() throws Exception {
        for (String ip : List.of("192.0.2.1", "192.0.2.2")) {
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(request("/api/auth/login").with(req -> { req.setRemoteAddr(ip); return req; }))
                        .andExpect(status().is(i < 2 ? 200 : 429));
            }
        }
        mockMvc.perform(request("/api/auth/login").with(req -> { req.setRemoteAddr("192.0.2.1"); return req; }))
                .andExpect(status().isTooManyRequests());
        verify(authService, times(4)).login(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/refresh", "/api/auth/logout"})
    void limitsCookieOperationsBeforeTokenLookup(String path) throws Exception {
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(request(path)).andExpect(status().is(i < 3 ? 200 : 429));
        }
        if (path.endsWith("/refresh")) verify(authService, times(3)).refresh(null);
        else verify(authService, times(3)).logout(null);
    }

    @Test
    void aFullIpAccountStillAdmitsAnotherIpsFirstLoginAndRegistration() throws Exception {
        // Six keys: one aggregate, three usernames, refresh and logout, all from the same IP.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(request("/api/auth/login").content(LOGIN_BODY.replace("victim", "name" + i)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(request("/api/auth/refresh")).andExpect(status().isOk());
        mockMvc.perform(request("/api/auth/logout")).andExpect(status().isOk());

        mockMvc.perform(request("/api/auth/login").with(req -> { req.setRemoteAddr("192.0.2.2"); return req; }))
                .andExpect(status().isOk());
        mockMvc.perform(request("/api/auth/register").content(REGISTER_BODY)
                        .with(req -> { req.setRemoteAddr("192.0.2.2"); return req; }))
                .andExpect(status().isCreated());
        mockMvc.perform(request("/api/auth/login")).andExpect(status().isTooManyRequests());
    }

    @Test
    void rejectsOversizedJsonBeforeLogin() throws Exception {
        mockMvc.perform(request("/api/auth/login").content(" ".repeat(1024 * 1024) + LOGIN_BODY))
                .andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(authService);
    }

}
