package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import com.arthur.jdragresume.service.AppUserService;
import com.arthur.jdragresume.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AppUserRateLimitTests {
    private MockMvc mockMvc;
    private PasswordEncoder passwords;
    private AppUser user;

    @BeforeEach
    void setUp() {
        user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 7L);
        user.setUsername("arthur");
        user.setEmail("old@example.com");
        user.setDisplayName("Arthur");
        user.setPasswordHash("stored-hash");

        AppUserRepository users = mock(AppUserRepository.class);
        when(users.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        passwords = mock(PasswordEncoder.class);
        when(passwords.matches(eq("correct123"), eq("stored-hash"))).thenReturn(true);
        CurrentUserService currentUser = new CurrentUserService(users) {
            @Override
            public AppUser getCurrentUser() {
                return user;
            }
        };
        AppUserService service = new AppUserService(users, passwords, currentUser,
                mock(RefreshTokenService.class), new SlidingWindowRateLimiter(() -> 1_000_000L));
        mockMvc = MockMvcBuilders.standaloneSetup(new AppUserController(service, new JwtProperties(), new ClientIpResolver("")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockHttpServletRequestBuilder update(String ip, String email, String password) {
        String currentPassword = password == null ? "" : ",\"currentPassword\":\"" + password + "\"";
        String body = "{\"username\":\"arthur\",\"email\":\"" + email
                + "\",\"displayName\":\"Arthur\"" + currentPassword + "}";
        return put("/api/users/me").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    @Test
    void sixthWrongPasswordIsRateLimitedWithoutAnotherBcryptCall() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(update("192.0.2.11", "new@example.com", "wrong123"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        }
        mockMvc.perform(update("192.0.2.11", "new@example.com", "wrong123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
        verify(passwords, times(5)).matches("wrong123", "stored-hash");
    }

    @Test
    void changingIpCannotResetTheUserIdFailureBucket() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(update("192.0.2.11", "new@example.com", "wrong123"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(update("192.0.2.12", "new@example.com", "wrong123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
        verify(passwords, times(5)).matches("wrong123", "stored-hash");
    }

    @Test
    void successfulPasswordChecksDoNotSpendUserFailureQuota() throws Exception {
        for (int i = 0; i < 7; i++) {
            String nextEmail = i % 2 == 0 ? "new@example.com" : "old@example.com";
            mockMvc.perform(update("192.0.2.11", nextEmail, "correct123"))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(update("192.0.2.11", "old@example.com", "wrong123"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        }
        mockMvc.perform(update("192.0.2.11", "old@example.com", "wrong123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
        verify(passwords, times(7)).matches("correct123", "stored-hash");
        verify(passwords, times(5)).matches("wrong123", "stored-hash");
    }

    @Test
    void sameIpCannotCircumventTheAggregateBucketByRotatingUsers() throws Exception {
        for (int i = 1; i <= 50; i++) {
            ReflectionTestUtils.setField(user, "id", (long) i);
            mockMvc.perform(update("192.0.2.11", "new@example.com", "wrong123"))
                    .andExpect(status().isBadRequest());
        }
        ReflectionTestUtils.setField(user, "id", 51L);
        mockMvc.perform(update("192.0.2.11", "new@example.com", "wrong123"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_RATE_LIMITED"));
        verify(passwords, times(50)).matches("wrong123", "stored-hash");
    }

    @Test
    void nicknameOnlyUpdatesDoNotSpendPasswordVerificationQuota() throws Exception {
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(update("192.0.2.11", "old@example.com", null))
                    .andExpect(status().isOk());
        }
        verifyNoInteractions(passwords);
        mockMvc.perform(update("192.0.2.11", "new@example.com", "wrong123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INVALID"));
        verify(passwords, times(1)).matches("wrong123", "stored-hash");
    }
}
