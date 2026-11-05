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

/**
 * The scrape endpoint answers only to the operator's scrape token. A valid user token must not open it:
 * instance-wide counters are operator data, not user data. This goes through the real {@link SecurityConfig}
 * wiring, so a reordered matcher or a dropped denyAll fails here. Health stays public on the same port.
 */
@WebMvcTest(controllers = PrometheusEndpointSecurityTests.ScrapeStandIn.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityProblemSupport.class, JwtService.class,
        BossPageBudgetConfig.class,
        PrometheusEndpointSecurityTests.ScrapeStandIn.class, PrometheusEndpointSecurityTests.Fakes.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-for-prometheus-endpoint-security-0123456789",
        "app.security.trusted-origins=http://localhost:3000",
        "app.metrics.scrape-token=" + PrometheusEndpointSecurityTests.SCRAPE_TOKEN,
})
class PrometheusEndpointSecurityTests {
    static final String SCRAPE_TOKEN = "scrape-token-for-tests-0123456789-abcdefghij";
    private static final AppUser ALICE = alice();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void theScrapeTokenIsServed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + SCRAPE_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousAndWrongTokensAreRefused() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + SCRAPE_TOKEN + "x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aValidUserTokenDoesNotOpenTheScrape() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                .header("Authorization", "Bearer " + jwtService.generateToken(ALICE)))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    private static AppUser alice() {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setUsername("alice");
        user.setPasswordHash("unused");
        return user;
    }

    /** Stands in for the actuator endpoints, which a slice test does not start. */
    @RestController
    static class ScrapeStandIn {
        @GetMapping("/actuator/prometheus")
        String scrape() {
            return "# HELP agent_turns_total\n";
        }

        @GetMapping("/actuator/health")
        String health() {
            return "{\"status\":\"UP\"}";
        }
    }

    /** Hand-written fakes: Mockito's inline mock maker cannot attach on this repo's Windows dev setup. */
    @TestConfiguration
    static class Fakes {
        @Bean
        AppUserRepository appUserRepository() {
            return (AppUserRepository) Proxy.newProxyInstance(
                    AppUserRepository.class.getClassLoader(),
                    new Class<?>[]{AppUserRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findByUsername" -> "alice".equals(args[0]) ? Optional.of(ALICE) : Optional.empty();
                        case "toString" -> "FakeAppUserRepository";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
