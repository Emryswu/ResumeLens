package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.auth.AuthResponse;
import com.arthur.jdragresume.dto.auth.LoginRequest;
import com.arthur.jdragresume.dto.auth.RegisterRequest;
import com.arthur.jdragresume.dto.user.UserResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class AuthService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final RefreshTokenService refreshTokenService;
    private final String dummyPasswordHash;

    public AuthService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties,
            RefreshTokenService refreshTokenService
    ) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.refreshTokenService = refreshTokenService;
        this.dummyPasswordHash = passwordEncoder.encode("resume-lens-timing-dummy");
    }

    @Transactional
    public AuthSession register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (!username.matches("^[A-Za-z0-9_.-]{3,64}$")
                || username.equalsIgnoreCase("anonymousUser")) {
            throw new BusinessException("INVALID_USERNAME",
                    "用户名需要 3 至 64 位，只能使用英文字母、数字、下划线、点或连字符，且不能使用保留名称");
        }
        if (appUserRepository.existsByUsername(username)
                || appUserRepository.existsByEmail(email)) {
            throw new BusinessException("ACCOUNT_CONFLICT", "an account with that username or email already exists");
        }

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setEmail(email);
        user.setDisplayName(request.displayName());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        AppUser savedUser = appUserRepository.save(user);

        return newSession(savedUser);
    }

    @Transactional
    public AuthSession login(LoginRequest request) {
        AppUser user = appUserRepository.findByUsername(request.username().trim()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyPasswordHash);
            throw new BadCredentialsException("username or password is incorrect");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("username or password is incorrect");
        }

        return newSession(user);
    }

    public AuthSession refresh(String rawRefreshToken) {
        RefreshTokenService.RotatedRefreshToken rotated = refreshTokenService.rotate(rawRefreshToken);
        return new AuthSession(buildAuthResponse(rotated.user()), rotated.refreshToken());
    }

    public void logout(String rawRefreshToken) {
        refreshTokenService.revoke(rawRefreshToken);
    }

    private AuthSession newSession(AppUser user) {
        RefreshTokenService.IssuedRefreshToken refreshToken = refreshTokenService.issue(user);
        return new AuthSession(buildAuthResponse(user), refreshToken);
    }

    private AuthResponse buildAuthResponse(AppUser user) {
        return new AuthResponse(
                "Bearer",
                jwtService.generateToken(user),
                jwtProperties.getExpirationMinutes() * 60,
                UserResponse.from(user)
        );
    }

    public record AuthSession(
            AuthResponse response,
            RefreshTokenService.IssuedRefreshToken refreshToken
    ) {
    }
}
