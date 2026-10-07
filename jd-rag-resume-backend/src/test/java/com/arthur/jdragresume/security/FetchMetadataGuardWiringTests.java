package com.arthur.jdragresume.security;

import com.arthur.jdragresume.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 防护挂在路径上，而不是靠每个接口记得调用：/api/auth 下新加一个接口，自己不写任何防护代码，
 * 跨站请求也到不了它。这里走的是 SecurityConfig 的真实装配（FilterRegistrationBean 的路径与顺序），
 * 不是在测试里手工 addFilter，所以路径写错、注册丢失都会让它失败。
 */
@WebMvcTest(controllers = FetchMetadataGuardWiringTests.BrandNewAuthEndpoint.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityProblemSupport.class, JwtService.class,
        FetchMetadataGuardWiringTests.BrandNewAuthEndpoint.class, FetchMetadataGuardWiringTests.Fakes.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-for-fetch-metadata-wiring-0123456789",
        "app.security.trusted-origins=http://localhost:3000",
})
class FetchMetadataGuardWiringTests {
    private static final String PATH = "/api/auth/brand-new-endpoint";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrandNewAuthEndpoint endpoint;

    @BeforeEach
    void reset() {
        endpoint.calls.clear();
    }

    @ParameterizedTest
    @CsvSource({
            "Sec-Fetch-Site, same-site",
            "Sec-Fetch-Site, cross-site",
            "Origin, https://evil.example.com",
            "Origin, null",
    })
    void aNewEndpointUnderApiAuthIsGuardedWithoutAnyCodeOfItsOwn(String header, String value) throws Exception {
        mockMvc.perform(post(PATH).header(header, value))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REQUEST_BLOCKED"));
        // SameSite=Lax 在跨站顶层导航的 GET 上仍会带 cookie，所以 GET 也拦
        mockMvc.perform(get(PATH).header(header, value))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REQUEST_BLOCKED"));

        assertEquals(List.of(), endpoint.calls, "the new endpoint must not be reached");
    }

    @Test
    void sameOriginRequestsStillReachIt() throws Exception {
        mockMvc.perform(post(PATH).header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
        mockMvc.perform(get(PATH).header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());

        assertEquals(List.of("POST", "GET"), endpoint.calls);
    }

    /** Stands in for an endpoint someone adds under /api/auth later, with no guard code of its own. */
    @RestController
    static class BrandNewAuthEndpoint {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        @PostMapping(PATH)
        void post() {
            calls.add("POST");
        }

        @GetMapping(PATH)
        void get() {
            calls.add("GET");
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
                        case "toString" -> "FakeAppUserRepository";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}
