package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.dto.user.UserResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.service.AppUserService;
import com.arthur.jdragresume.service.RefreshTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AppUserControllerTests {
    @Test
    void passwordChangeSetsReplacementRefreshCookieForCurrentBrowser() {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 9L);
        user.setUsername("arthur");
        user.setEmail("arthur@example.com");
        user.setDisplayName("Arthur");
        AppUserService service = new AppUserService(null, null, null, null) {
            @Override
            public UpdatedUser updateMe(UserRequest request) {
                return new UpdatedUser(UserResponse.from(user),
                        new RefreshTokenService.IssuedRefreshToken(
                                "replacement-token", LocalDateTime.of(2026, 11, 12, 8, 0)));
            }
        };
        JwtProperties properties = new JwtProperties();
        properties.setRefreshExpirationDays(7);
        AppUserController controller = new AppUserController(service, properties);
        var response = controller.updateMe(
                new UserRequest("arthur", "arthur@example.com", "Arthur", "new-password", "old-password"),
                new MockHttpServletRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("arthur", response.getBody().data().username());
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.contains("jd-rag-refresh=replacement-token"), cookie);
        assertTrue(cookie.contains("Path=/api/auth"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
    }
}
