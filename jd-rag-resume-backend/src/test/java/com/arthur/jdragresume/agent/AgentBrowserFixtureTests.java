package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.controller.AgentController;
import com.arthur.jdragresume.controller.AgentConversationController;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.GlobalExceptionHandler;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletRegistrationBean;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.DispatcherServlet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Opt-in real HTTP/MySQL fixture for browser tests. It never uses the developer's database or credentials. */
@EnabledIfSystemProperty(named = "agent.browser.fixture", matches = ".+")
class AgentBrowserFixtureTests extends ConversationStoreTests {
    @Test void serveBrowserTests() throws Exception {
        Path ready = Path.of(System.getProperty("agent.browser.fixture"));
        Path stop = Path.of(ready + ".stop");
        var users = new CurrentUserService(null) {
            @Override public AppUser getCurrentUser() {
                var request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
                var user = new AppUser();
                ReflectionTestUtils.setField(user, "id", "Bearer bob".equals(request.getHeader("Authorization")) ? 2L : 1L);
                return user;
            }
        };
        List<AgentTool> tools = new java.util.ArrayList<>();
        tools.add(tool("list_resumes", Map.of("resumes", List.of(Map.of("resumeId", 1, "title", "Java 简历"))), false));
        tools.add(tool("rank_jobs_for_resume", Map.of("matches", List.of(Map.of("jobId", 1, "title", "Java 后端", "companyName", "测试公司", "similarity", 0.8))), false));
        tools.add(tool("search_resume_evidence", Map.of("kept", List.of(), "threshold", 0.1, "filteredCount", 0), false));
        tools.add(tool("start_analysis", Map.of("analysisId", 7), true));
        tools.add(new AgentBrowserTaskTests.CollectTool());
        var registry = new AgentToolRegistry(tools);
        var model = new ScriptedAgentModel(ConversationStore.JSON) {
            @Override public Reply next(String system, List<AgentMessage> transcript, List<ToolDefinition> definitions, Duration timeout) {
                String question = transcript.stream().filter(message -> AgentMessage.USER.equals(message.role())).reduce((a,b) -> b).orElseThrow().content();
                if (question.contains("慢一点")) {
                    try { Thread.sleep(1000); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
                }
                if (question.contains("浏览器任务")) {
                    boolean result = transcript.stream().anyMatch(message -> AgentMessage.TOOL.equals(message.role()));
                    return result ? Reply.answer("采集已结束") : Reply.call(AgentMessage.ToolCall.of("browser", "collect_boss_jobs", "{\"keyword\":\"Java\",\"city\":\"深圳\"}"));
                }
                return super.next(system, transcript, definitions, timeout);
            }
        };
        var properties = new AgentProperties();
        properties.setMaxTurnsPerWindow(500);
        var tasks = new BrowserTaskRegistry(store);
        var loop = new AgentLoop(model, registry, ConversationStore.JSON, properties, System::currentTimeMillis, tasks);
        var memories = new AgentMemoryService(null, null, null, null) {
            @Override public List<MemoryView> rememberAndRecall(String question, boolean remember) { return List.of(); }
        };
        var service = new AgentChatService(users, new TranscriptPolicy(properties), loop, new SlidingWindowRateLimiter(),
                command -> Thread.ofVirtual().start(command), properties, memories, registry, tasks, store);
        try (var web = new AnnotationConfigServletWebServerApplicationContext()) {
            web.register(AgentDisconnectTests.Web.class);
            web.registerBean(TomcatServletWebServerFactory.class, () -> new TomcatServletWebServerFactory(0));
            web.registerBean(DispatcherServlet.class, () -> new DispatcherServlet(web));
            web.registerBean(DispatcherServletRegistrationBean.class,
                    () -> new DispatcherServletRegistrationBean(web.getBean(DispatcherServlet.class), "/"));
            web.registerBean(AgentController.class, () -> new AgentController(service));
            web.registerBean(AgentConversationController.class, () -> new AgentConversationController(store, users));
            web.registerBean(GlobalExceptionHandler.class);
            web.refresh();
            Files.createDirectories(ready.getParent());
            Files.writeString(ready, "{\"port\":" + web.getWebServer().getPort() + "}");
            long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(100);
        }
    }

    private static AgentTool tool(String name, Object result, boolean confirmation) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ToolSchema parameters() {
                return name.equals("list_resumes") ? ToolSchema.object() : name.equals("start_analysis") || name.equals("search_resume_evidence")
                        ? ToolSchema.object().integer("resumeId", "resume", true, 1, 100).integer("jobId", "job", true, 1, 100)
                        : ToolSchema.object().integer("resumeId", "resume", true, 1, 100).integer("limit", "limit", false, 1, 10);
            }
            @Override public boolean requiresConfirmation() { return confirmation; }
            @Override public Object execute(JsonNode arguments, AgentToolContext context) { return result; }
            @Override public Object preview(JsonNode arguments, AgentToolContext context) { return Map.of("resumeTitle", "Java 简历", "jobTitle", "Java 后端"); }
        };
    }
}
