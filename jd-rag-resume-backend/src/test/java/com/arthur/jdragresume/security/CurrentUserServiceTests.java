package com.arthur.jdragresume.security;

import com.arthur.jdragresume.dto.user.UserRequest;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.repository.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserServiceTests {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousPrincipalNeverResolvesEvenIfThereIsAnAnonymousUserRow() {
        AppUser anonymousUser = new AppUser();
        anonymousUser.setUsername("anonymousUser");
        AppUserRepository repository = (AppUserRepository) Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(),
                new Class<?>[]{AppUserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findByUsername" -> Optional.of(anonymousUser);
                    case "findById" -> Optional.of(anonymousUser);
                    case "toString" -> "FakeAppUserRepository";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        ));
        assertThrows(IllegalStateException.class, new CurrentUserService(repository)::getCurrentUser);
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous", new JwtUserPrincipal(7L, "anonymousUser"),
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        ));
        assertThrows(IllegalStateException.class, new CurrentUserService(repository)::getCurrentUser);
    }

    @Test
    void jwtPrincipalResolvesUserByIdWithoutUsernameLookup() {
        AppUser user = new AppUser();
        user.setUsername("alice");
        AppUserRepository repository = (AppUserRepository) Proxy.newProxyInstance(
                AppUserRepository.class.getClassLoader(),
                new Class<?>[]{AppUserRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> Long.valueOf(5L).equals(args[0]) ? Optional.of(user) : Optional.empty();
                    default -> throw new AssertionError("Only lookup by ID is allowed: " + method.getName());
                }
        );
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new JwtUserPrincipal(5L, "ALICE"), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        ));
        assertSame(user, new CurrentUserService(repository).getCurrentUser());
    }

    @Test
    void userRequestHasSeparateCurrentPassword() {
        assertTrue(Arrays.stream(UserRequest.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("currentPassword")));
    }
}
