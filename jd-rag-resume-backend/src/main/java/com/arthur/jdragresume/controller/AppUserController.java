package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.dto.user.UserResponse;
import com.arthur.jdragresume.service.AppUserService;
import com.arthur.jdragresume.security.CookieSecurity;
import com.arthur.jdragresume.security.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class AppUserController {
    private final AppUserService appUserService;
    private final JwtProperties jwtProperties;
    private final ClientIpResolver clientIps;

    public AppUserController(AppUserService appUserService, JwtProperties jwtProperties, ClientIpResolver clientIps) {
        this.appUserService = appUserService;
        this.jwtProperties = jwtProperties;
        this.clientIps = clientIps;
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> findMe() {
        return ApiResponse.ok(appUserService.findMe());
    }

    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(
            @Valid @RequestBody UserRequest request,
            HttpServletRequest httpRequest
    ) {
        AppUserService.UpdatedUser updated = appUserService.updateMe(request, clientIps.resolve(httpRequest));
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (updated.refreshToken() != null) {
            ResponseCookie cookie = ResponseCookie.from("jd-rag-refresh", updated.refreshToken().value())
                    .httpOnly(true)
                    .secure(CookieSecurity.secure(jwtProperties.isRefreshCookieSecure(), httpRequest.isSecure()))
                    .sameSite("Lax")
                    .path("/api/auth")
                    .maxAge(Duration.ofDays(jwtProperties.getRefreshExpirationDays()))
                    .build();
            response.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return response.body(ApiResponse.ok(updated.user()));
    }
}
