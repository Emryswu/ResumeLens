package com.arthur.jdragresume.security;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import com.arthur.jdragresume.collect.BossPageBudgetConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Proxy;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AiStatusAuthenticationTests.StatusEndpoint.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityProblemSupport.class, JwtService.class,
        BossPageBudgetConfig.class,
        AiStatusAuthenticationTests.StatusEndpoint.class, AiStatusAuthenticationTests.Fakes.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-for-ai-status-regression-0123456789",
        "app.security.trusted-origins=http://localhost:3000",
})
class AiStatusAuthenticationTests {
    private static final AppUser USER = user();

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    @Test
    void anonymousStatusRequestIsDeniedWhileAuthenticatedUserCanReadIt() throws Exception {
        mvc.perform(get("/api/ai/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/status")
                        .header("Authorization", "Bearer " + jwt.generateToken(USER)))
                .andExpect(status().isOk());
    }

    private static AppUser user() {
        AppUser value = new AppUser();
        ReflectionTestUtils.setField(value, "id", 1L);
        value.setUsername("alice");
        value.setPasswordHash("unused");
        return value;
    }

    @RestController
    static class StatusEndpoint {
        @GetMapping("/api/ai/status")
        String status() {
            return "ok";
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        AppUserRepository appUserRepository() {
            return (AppUserRepository) Proxy.newProxyInstance(
                    AppUserRepository.class.getClassLoader(), new Class<?>[]{AppUserRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findByUsername" -> "alice".equals(args[0]) ? Optional.of(USER) : Optional.empty();
                        case "toString" -> "FakeAppUserRepository";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
