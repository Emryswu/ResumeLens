package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.RelaxJobSearchConditionTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 完全访问 only lets the page start the read-only BOSS tasks listed in {@link FullAccessPolicy} without a click.
 * It never skips a confirmation card, and the budget is checked before any task is issued, with or without it.
 */
class FullAccessTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppUser user = new AppUser();
    private final AtomicReference<JobSearchContext> stored = new AtomicReference<>();
    private final List<Map.Entry<String, Object>> events = new ArrayList<>();
    private final AtomicInteger collections = new AtomicInteger();
    private ConversationStore store;
    private ConversationStore.Run run;
    private boolean fullAccess;
    /** The collector finishes on the server with this many daily pages left; null hands the task to the browser. */
    private Integer serverDailyPagesLeft;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", 7L);
        stored.set(JobSearchContext.empty().selectedResume(3).preferencesRead());
        store = mock(ConversationStore.class);
        when(store.searchContext(any())).thenAnswer(invocation -> stored.get());
        when(store.updateSearchContext(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            UnaryOperator<JobSearchContext> update = invocation.getArgument(1);
            JobSearchContext next = update.apply(stored.get());
            stored.set(next);
            return next;
        });
        when(store.fullAccess(any())).thenAnswer(invocation -> fullAccess);
        run = new ConversationStore.Run(7L, 11L, "turn", "run", null, null, 0);
    }

    @Test
    void withFullAccessCollectAndVerifyStartWithoutTheUsersClick() {
        fullAccess = true;
        AgentLoop.Outcome collect = runTurn(call("c1", JobSearchWorkflow.TOOL, "{\"keyword\":\"Java\",\"city\":\"深圳\"}"));
        assertEquals(AgentLoop.State.AWAITING_BROWSER, collect.state(), "the browser still does the work");
        assertTrue(browserTask().autoStart(), "nothing waits for the user to click 继续采集");
        assertNoConfirmation();

        events.clear();
        AgentLoop.Outcome verify = runTurn(call("v1", JobSearchWorkflow.VERIFY_TOOL, "{\"jobId\":42}"));
        assertEquals(AgentLoop.State.AWAITING_BROWSER, verify.state());
        assertTrue(browserTask().autoStart(), "nothing waits for the user to click 继续核实");
        assertNoConfirmation();
    }

    @Test
    void withoutFullAccessEveryBrowserTaskWaitsForTheUser() {
        fullAccess = false;
        runTurn(call("c1", JobSearchWorkflow.TOOL, "{\"keyword\":\"Java\",\"city\":\"深圳\"}"));
        assertFalse(browserTask().autoStart());

        events.clear();
        runTurn(call("v1", JobSearchWorkflow.VERIFY_TOOL, "{\"jobId\":42}"));
        assertFalse(browserTask().autoStart());
    }

    @Test
    void withFullAccessRelaxingAHardConditionStillNeedsTheUsersApproval() {
        fullAccess = true;
        stored.set(stored.get().patch(mapper.createObjectNode().put("jobType", "实习")));
        AgentLoop.Outcome outcome = runTurn(call("r1", RelaxJobSearchConditionTool.TOOL, "{\"experience\":\"不限\"}"));
        assertEquals(AgentLoop.State.AWAITING_CONFIRMATION, outcome.state());
        assertTrue(events.stream().anyMatch(event -> "confirmation_required".equals(event.getKey())));
        assertNull(stored.get().experience(), "nothing is loosened before the user approves");
    }

    @Test
    void withFullAccessAnExhaustedDailyBudgetIsStillRefusedBeforeAnyTaskIsIssued() throws Exception {
        fullAccess = true;
        serverDailyPagesLeft = 0;
        AgentLoop.Outcome outcome = runTurn(
                call("c1", JobSearchWorkflow.TOOL, "{\"keyword\":\"Java\",\"city\":\"深圳\"}"),
                call("c2", JobSearchWorkflow.TOOL, "{\"keyword\":\"Go\",\"city\":\"深圳\"}"),
                call("v1", JobSearchWorkflow.VERIFY_TOOL, "{\"jobId\":42}"));

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, collections.get(), "only the first call reached the collector");
        assertTrue(events.stream().noneMatch(event -> "browser_task_required".equals(event.getKey())),
                "no browser task is handed out once the daily pages are used up");
        List<JsonNode> results = new ArrayList<>();
        for (AgentMessage message : outcome.transcript()) {
            if (AgentMessage.TOOL.equals(message.role())) results.add(mapper.readTree(message.content()));
        }
        assertEquals(3, results.size());
        for (JsonNode refused : results.subList(1, 3)) {
            assertEquals(JobSearchWorkflow.BUDGET_ERROR, refused.path("error").path("code").asText(), refused.toString());
        }
    }

    @Test
    void onlyReadOnlyBudgetGovernedBrowserToolsCanBeSkipped() throws Exception {
        Map<String, AgentTool> tools = productionTools();
        for (String name : FullAccessPolicy.SKIPS_USER_START) {
            AgentTool tool = tools.get(name);
            assertNotNull(tool, name + " is not a registered tool");
            assertInstanceOf(DeferrableTool.class, tool, name + ": only browser tasks have a click to skip");
            assertFalse(tool.requiresConfirmation(), name + ": a confirmation card is an authorization and is never skipped");
            assertTrue(JobSearchWorkflow.governs(name), name + ": a skippable task must be checked by the BOSS budget");
        }
        for (AgentTool tool : tools.values()) {
            if (tool.requiresConfirmation()) {
                assertFalse(FullAccessPolicy.autoStart(true, tool.name()), tool.name() + " keeps its card");
            }
        }
        assertFalse(FullAccessPolicy.autoStart(true, RelaxJobSearchConditionTool.TOOL));
        assertFalse(FullAccessPolicy.autoStart(false, JobSearchWorkflow.TOOL));
    }

    @Test
    void theAllowListIsReadInOnePlace() throws IOException {
        Path main = Path.of("src", "main", "java");
        List<String> readers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("FullAccessPolicy.java")) continue;
                String source = Files.readString(file);
                if (source.contains("SKIPS_USER_START") || source.contains("FullAccessPolicy.autoStart")) {
                    readers.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("AgentLoop.java"), readers, "decide what 完全访问 skips only through FullAccessPolicy");
        String loop = Files.readString(main.resolve(Path.of("com", "arthur", "jdragresume", "agent", "AgentLoop.java")));
        assertEquals(1, loop.split("FullAccessPolicy\\.autoStart", -1).length - 1);
    }

    private Map<String, AgentTool> productionTools() throws Exception {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(AgentTool.class));
        Map<String, AgentTool> tools = new HashMap<>();
        for (var candidate : scanner.findCandidateComponents("com.arthur.jdragresume.agent.tool")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            var tool = (AgentTool) org.mockito.Mockito.mock(type, org.mockito.Mockito.CALLS_REAL_METHODS);
            tools.put(tool.name(), tool);
        }
        return tools;
    }

    private AgentLoop.BrowserTaskEvent browserTask() {
        return events.stream().filter(event -> "browser_task_required".equals(event.getKey()))
                .map(event -> (AgentLoop.BrowserTaskEvent) event.getValue()).findFirst().orElseThrow();
    }

    private void assertNoConfirmation() {
        assertTrue(events.stream().noneMatch(event -> "confirmation_required".equals(event.getKey())));
    }

    private static AgentMessage.ToolCall call(String id, String name, String arguments) {
        return AgentMessage.ToolCall.of(id, name, arguments);
    }

    /** One user turn whose model makes these calls one at a time, then answers. */
    private AgentLoop.Outcome runTurn(AgentMessage.ToolCall... calls) {
        AtomicInteger next = new AtomicInteger();
        AgentModel model = (system, messages, tools, timeout) -> {
            int index = next.getAndIncrement();
            return index < calls.length ? AgentModel.Reply.call(calls[index]) : AgentModel.Reply.answer("好的");
        };
        var registry = new AgentToolRegistry(List.of(collector(), verifier(),
                new RelaxJobSearchConditionTool(store, userId -> true)));
        var loop = new AgentLoop(model, registry, mapper, new AgentProperties(), System::currentTimeMillis,
                new BrowserTaskRegistry(), store, userId -> true);
        var policy = new TranscriptPolicy(new AgentProperties());
        return loop.run(policy.normalize(new ArrayList<>(List.of(AgentMessage.user("找岗位"))), null), null,
                new AgentToolContext(user, run), new AgentEventSink() {
                    @Override public void emit(String name, Object payload) { events.add(Map.entry(name, payload)); }
                    @Override public void checkpoint(List<AgentMessage> messages, String summary) { }
                }, () -> false);
    }

    private DeferrableTool collector() {
        return new DeferrableTool() {
            public String name() { return JobSearchWorkflow.TOOL; }
            public String description() { return "collect"; }
            public ToolSchema parameters() {
                return ToolSchema.object()
                        .string("keyword", "keyword", false, 50)
                        .string("city", "city", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(JsonNode args, AgentToolContext context) {
                collections.incrementAndGet();
                if (serverDailyPagesLeft == null) {
                    return new InBrowser("browser-extension", "collectBossJobs", Map.of("keyword", args.path("keyword").asText()));
                }
                return new Done(Map.of("status", "COMPLETED", "finishReason", "MAX_PAGES", "pagesRead", 1,
                        "jobIds", List.of(42L), "dailyPagesLeft", serverDailyPagesLeft));
            }
            public Object resume(JsonNode args, JsonNode report, AgentToolContext context) {
                throw new AssertionError("not reported in these tests");
            }
        };
    }

    private DeferrableTool verifier() {
        return new DeferrableTool() {
            public String name() { return JobSearchWorkflow.VERIFY_TOOL; }
            public String description() { return "verify"; }
            public ToolSchema parameters() {
                return ToolSchema.object().integer("jobId", "job", true, 1, Integer.MAX_VALUE);
            }
            public Begin begin(JsonNode args, AgentToolContext context) {
                return new InBrowser("browser-extension", "verifyBossJob", Map.of("jobId", args.path("jobId").asLong()));
            }
            public Object resume(JsonNode args, JsonNode report, AgentToolContext context) {
                throw new AssertionError("not reported in these tests");
            }
        };
    }
}
