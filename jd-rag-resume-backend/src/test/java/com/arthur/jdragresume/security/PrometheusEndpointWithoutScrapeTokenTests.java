package com.arthur.jdragresume.security;

import com.arthur.jdragresume.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Proxy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The default deployment sets no scrape token: then the endpoint answers to nobody, whatever they present. */
@WebMvcTest(controllers = PrometheusEndpointSecurityTests.ScrapeStandIn.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityProblemSupport.class, JwtService.class,
        PrometheusEndpointSecurityTests.ScrapeStandIn.class, PrometheusEndpointWithoutScrapeTokenTests.Fakes.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-for-prometheus-no-scrape-token-0123456789",
        "app.security.trusted-origins=http://localhost:3000",
})
class PrometheusEndpointWithoutScrapeTokenTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void scrapeIsRefusedWithAnyOrNoToken() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus")
                .header("Authorization", "Bearer " + PrometheusEndpointSecurityTests.SCRAPE_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        AppUserRepository appUserRepository() {
            return (AppUserRepository) Proxy.newProxyInstance(
                    AppUserRepository.class.getClassLoader(),
                    new Class<?>[]{AppUserRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> "FakeAppUserRepository";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
