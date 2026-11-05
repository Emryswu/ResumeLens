package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.dto.user.UserResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;

import java.util.Locale;
import java.util.Objects;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppUserService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUserService;
    private final RefreshTokenService refreshTokenService;

    public AppUserService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            CurrentUserService currentUserService,
            RefreshTokenService refreshTokenService
    ) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.currentUserService = currentUserService;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional(readOnly = true)
    public UserResponse findMe() {
        return UserResponse.from(currentUserService.getCurrentUser());
    }

    @Transactional
    public UpdatedUser updateMe(UserRequest request) {
        AppUser user = currentUserService.getCurrentUser();
        boolean passwordChanged = request.password() != null && !request.password().isBlank();
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        boolean emailChanged = !user.getEmail().equalsIgnoreCase(email);
        if (emailChanged || passwordChanged) {
            if (request.currentPassword() == null || request.currentPassword().isBlank()
                    || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new BusinessException("CURRENT_PASSWORD_INVALID", "当前密码不正确，请重新输入");
            }
        }
        String username = request.username().trim();
        if (!user.getUsername().trim().equalsIgnoreCase(username)
                && appUserRepository.findByUsername(username)
                .map(other -> !Objects.equals(user.getId(), other.getId())).orElse(true)) {
            throw new BusinessException("USERNAME_IMMUTABLE", "用户名不能修改");
        }
        if (emailChanged && appUserRepository.existsByEmail(email)) {
            throw new BusinessException("ACCOUNT_CONFLICT", "该邮箱已被其他账号使用");
        }
        user.setEmail(email);
        user.setDisplayName(request.displayName());
        if (passwordChanged) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
        }
        AppUser savedUser = appUserRepository.save(user);
        if (passwordChanged) {
            refreshTokenService.revokeAllForUser(savedUser.getId());
            return new UpdatedUser(UserResponse.from(savedUser), refreshTokenService.issue(savedUser));
        }
        return new UpdatedUser(UserResponse.from(savedUser), null);
    }

    @Transactional(readOnly = true)
    public AppUser getEntity(Long id) {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("user", id));
    }

    public record UpdatedUser(UserResponse user, RefreshTokenService.IssuedRefreshToken refreshToken) {
    }
}
