package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.controller.AgentController;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.repository.AppUserRepository;
import com.arthur.jdragresume.security.JwtAuthenticationFilter;
import com.arthur.jdragresume.security.JwtService;
import com.arthur.jdragresume.security.SecurityConfig;
import com.arthur.jdragresume.security.SecurityProblemSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the real security filter chain around the SSE endpoint. An SSE response finishes
 * with an ASYNC dispatch of the same request; the JWT filter does not run on it, so it must
 * not be subjected to authorization again.
 */
@WebMvcTest(controllers = AgentController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityProblemSupport.class, JwtService.class,
        AgentAsyncDispatchSecurityTests.Fakes.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-for-agent-async-dispatch-0123456789")
class AgentAsyncDispatchSecurityTests {
    private static final String BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}]}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void completedStreamIsNotRejectedOnTheAsyncDispatch() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/agent/chat")
                        .header("Authorization", "Bearer " + jwtService.generateToken(ALICE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(request().asyncStarted())
                .andReturn();
        started.getAsyncResult(5_000);

        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("event:done"), body);
    }

    @Test
    void theInitialRequestStillRequiresAToken() throws Exception {
        mockMvc.perform(post("/api/agent/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    private static final AppUser ALICE = alice();

    private static AppUser alice() {
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 1L);
        user.setUsername("alice");
        user.setPasswordHash("unused");
        return user;
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

        @Bean
        AgentChatService agentChatService() {
            return new AgentChatService(null, null, null, null, null, null) {
                @Override
                public SseEmitter chat(List<AgentMessage> messages, TranscriptPolicy.Approval approval) {
                    SseEmitter emitter = new SseEmitter(5_000L);
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(50);
                            emitter.send(SseEmitter.event().name("done").data("{\"state\":\"COMPLETED\"}"));
                            emitter.complete();
                        } catch (Exception ex) {
                            emitter.completeWithError(ex);
                        }
                    });
                    return emitter;
                }
            };
        }
    }
}
