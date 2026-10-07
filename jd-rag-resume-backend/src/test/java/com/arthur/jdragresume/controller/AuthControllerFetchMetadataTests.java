package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.dto.auth.LoginRequest;
import com.arthur.jdragresume.dto.auth.RegisterRequest;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.security.FetchMetadataGuard;
import com.arthur.jdragresume.security.JwtProperties;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import com.arthur.jdragresume.service.AuthService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * register / login / refresh / logout 读写 refresh cookie，而 cookie 由浏览器自动携带。
 * SameSite=Lax 按「站点」判断，兄弟子域（same-site 但不同源）的 POST 仍会带上它，
 * 所以同站也必须拦；并且要在任何副作用之前拦——包括消耗限流额度。
 */
class AuthControllerFetchMetadataTests {
    private static final String LOGIN_BODY = """
            {"username":"victim","password":"secret123"}
            """;
    private static final String REGISTER_BODY = """
            {"username":"attacker","email":"a@example.com","displayName":"A","password":"secret123"}
            """;

    private MockMvc mockMvc;
    private RecordingAuthService authService;
    private RecordingRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        authService = new RecordingAuthService();
        rateLimiter = new RecordingRateLimiter();
        // 防护不在 controller 里，而是挂在 /api/auth/* 上的过滤器，与 SecurityConfig 同一路径。
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(authService, new JwtProperties(), rateLimiter, 20, 15, 8, 30))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilter(new FetchMetadataGuard(List.of("http://localhost:3000"),
                        Jackson2ObjectMapperBuilder.json().build()), "/api/auth/*")
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/auth/refresh, same-site",
            "/api/auth/refresh, cross-site",
            "/api/auth/logout, same-site",
            "/api/auth/logout, cross-site",
            "/api/auth/login, same-site",
            "/api/auth/login, cross-site",
            "/api/auth/register, same-site",
            "/api/auth/register, cross-site",
    })
    void rejectsNonSameOriginRequestsBeforeAnySideEffect(String path, String site) throws Exception {
        mockMvc.perform(request(path).header("Sec-Fetch-Site", site))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REQUEST_BLOCKED"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertEquals(List.of(), authService.calls, "auth service must not be reached");
        assertEquals(List.of(), rateLimiter.keys, "rate-limit budget must not be spent");
    }

    @Test
    void sameOriginAndNonBrowserRequestsStillReachTheService() throws Exception {
        mockMvc.perform(request("/api/auth/logout").header("Sec-Fetch-Site", "same-origin"))
                .andExpect(status().isOk());
        mockMvc.perform(request("/api/auth/logout").header("Sec-Fetch-Site", "none"))
                .andExpect(status().isOk());
        mockMvc.perform(request("/api/auth/logout"))
                .andExpect(status().isOk());

        assertEquals(List.of("logout:victim-refresh", "logout:victim-refresh", "logout:victim-refresh"),
                authService.calls);
    }

    // 旧浏览器不发 Sec-Fetch-Site，但跨源 POST 一定带 Origin。缺头不能等于放行。
    @ParameterizedTest
    @CsvSource({
            "https://evil.example.com",
            "null",
            // 与可信来源 http://localhost:3000 只差协议 / 只差端口，都不是同源
            "https://localhost:3000",
            "http://localhost:8081",
    })
    void untrustedOriginWithoutFetchMetadataIsRejectedBeforeAnySideEffect(String origin) throws Exception {
        mockMvc.perform(request("/api/auth/logout").header("Origin", origin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_REQUEST_BLOCKED"));
        mockMvc.perform(request("/api/auth/login").header("Origin", origin))
                .andExpect(status().isForbidden());

        assertEquals(List.of(), authService.calls, "auth service must not be reached");
        assertEquals(List.of(), rateLimiter.keys, "rate-limit budget must not be spent");
    }

    @Test
    void trustedOriginWithoutFetchMetadataStillReachesTheService() throws Exception {
        mockMvc.perform(request("/api/auth/logout").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk());

        assertEquals(List.of("logout:victim-refresh"), authService.calls);
    }

    private static MockHttpServletRequestBuilder request(String path) {
        MockHttpServletRequestBuilder builder = post(path).cookie(new Cookie("jd-rag-refresh", "victim-refresh"));
        if (path.endsWith("/login")) {
            return builder.contentType(MediaType.APPLICATION_JSON).content(LOGIN_BODY);
        }
        if (path.endsWith("/register")) {
            return builder.contentType(MediaType.APPLICATION_JSON).content(REGISTER_BODY);
        }
        return builder;
    }

    /**
     * 记录调用；会返回会话的方法记录后抛业务异常，这样未拦截时测试以
     * 「期望 403 实际 400」失败，而不是在拼 cookie 时 NPE。
     */
    private static final class RecordingAuthService extends AuthService {
        private final List<String> calls = new CopyOnWriteArrayList<>();

        private RecordingAuthService() {
            super(null, new BCryptPasswordEncoder(4), null, null, null);
        }

        @Override
        public AuthSession register(RegisterRequest request) {
            calls.add("register:" + request.username());
            throw new BusinessException("REACHED_AUTH_SERVICE", "register reached the service");
        }

        @Override
        public AuthSession login(LoginRequest request) {
            calls.add("login:" + request.username());
            throw new BusinessException("REACHED_AUTH_SERVICE", "login reached the service");
        }

        @Override
        public AuthSession refresh(String rawRefreshToken) {
            calls.add("refresh:" + rawRefreshToken);
            throw new BusinessException("REACHED_AUTH_SERVICE", "refresh reached the service");
        }

        @Override
        public void logout(String rawRefreshToken) {
            calls.add("logout:" + rawRefreshToken);
        }
    }

    private static final class RecordingRateLimiter extends SlidingWindowRateLimiter {
        private final List<String> keys = new CopyOnWriteArrayList<>();

        @Override
        public boolean tryAcquire(String key, int limit, long windowMs) {
            keys.add(key);
            return true;
        }
    }
}
