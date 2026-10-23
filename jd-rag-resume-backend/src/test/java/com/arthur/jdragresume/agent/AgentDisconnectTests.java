package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.controller.AgentController;
import com.arthur.jdragresume.controller.AgentConversationController;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletRegistrationBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentDisconnectTests extends ConversationStoreTests {
    @Configuration @EnableWebMvc
    static class Web {}

    @Test
    void actualHttpDisconnectKeepsRunningAndCheckpointsBeforeDone() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var properties = new AgentProperties();
        var model = new ScriptedAgentModel(ConversationStore.JSON) {
            @Override public Reply next(String system, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout) {
                try {
                    if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    else { second.countDown(); assertTrue(finish.await(10, TimeUnit.SECONDS)); }
                } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                return super.next(system, transcript, tools, timeout);
            }
        };
        AppUser alice = new AppUser();
        ReflectionTestUtils.setField(alice, "id", 1L);
        var users = new CurrentUserService(null) { @Override public AppUser getCurrentUser() { return alice; } };
        var registry = new AgentToolRegistry(List.of());
        var tasks = new BrowserTaskRegistry(store);
        var loop = new AgentLoop(model, registry, ConversationStore.JSON, properties, System::currentTimeMillis, tasks);
        var memories = new AgentMemoryService(null, null, null, null) {
            @Override public List<MemoryView> rememberAndRecall(String question, boolean remember) { return List.of(); }
        };
        var service = new AgentChatService(users, new TranscriptPolicy(properties), loop, new SlidingWindowRateLimiter(),
                command -> Thread.ofVirtual().start(command), properties, memories, registry, tasks, store);
        try (var web = new AnnotationConfigServletWebServerApplicationContext()) {
            web.register(Web.class);
            web.registerBean(TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
            web.registerBean(DispatcherServlet.class, () -> new DispatcherServlet(web));
            web.registerBean(DispatcherServletRegistrationBean.class,
                    () -> new DispatcherServletRegistrationBean(web.getBean(DispatcherServlet.class), "/"));
            web.registerBean(AgentController.class, () -> new AgentController(service));
            web.registerBean(AgentConversationController.class, () -> new AgentConversationController(store, users));
            web.registerBean(GlobalExceptionHandler.class);
            web.refresh();
            long id = store.create(1).conversation().id();
            URI url = URI.create("http://localhost:" + web.getWebServer().getPort() + "/api/agent/chat");
            try (var client = HttpClient.newHttpClient()) {
                var response = client.send(HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":"+id+",\"question\":\"你好\"}")).build(), HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, response.statusCode());
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                response.body().close();
                release.countDown();
                assertTrue(second.await(10, TimeUnit.SECONDS));
                assertTrue(store.messages(id).stream().anyMatch(message -> AgentMessage.TOOL.equals(message.role())));
                var partial = store.detail(1, id, 0);
                assertEquals("RUNNING", partial.conversation().state());
                assertTrue(partial.events().stream().anyMatch(event -> event.type().equals("step")));
                assertFalse(partial.events().stream().anyMatch(event -> event.type().equals("done")));
                finish.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (store.detail(1, id, 0).conversation().activeRun() != null && System.nanoTime() < deadline) Thread.sleep(20);
                var done = store.detail(1, id, 0);
                assertEquals("COMPLETED", done.conversation().state());
                assertFalse(done.turns().get(0).path("answer").asText().isBlank());
                assertEquals("done", done.events().getLast().type());
            } finally { release.countDown(); finish.countDown(); }
        }
    }
}
