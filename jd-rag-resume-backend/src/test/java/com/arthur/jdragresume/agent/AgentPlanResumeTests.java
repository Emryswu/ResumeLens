package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.AgentLoopTests.RecordingSink;
import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A turn that pauses for the browser and resumes on its report must be indistinguishable from the
 * same turn run straight through: the accepted plan, its cursor, a pending replan and the replans
 * already used all come back from the server-held transcript, never from a fresh start.
 */
class AgentPlanResumeTests {
    private static final String QUESTION = "帮我去 BOSS 找深圳 Java 后端岗位";
    private static final List<String> SEARCH_PLAN = List.of(
            "get_resume", "get_job_search_preferences", "collect_boss_jobs", "rank_jobs_for_resume");
    private static final Map<String, Object> FOUND = Map.of("pagesRead", 1, "jobIds", List.of(42),
            "status", "COMPLETED", "finishReason", "MAX_PAGES", "dailyPagesLeft", 10);
    private static final Map<String, Object> NOTHING_FOUND = Map.of("pagesRead", 1, "jobIds", List.of(),
            "status", "COMPLETED", "finishReason", "NO_RESULTS", "dailyPagesLeft", 10);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AppUser user = new AppUser();
    private final AgentToolContext context = new AgentToolContext(user);
    private final AgentProperties properties = new AgentProperties();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<String> executed = new ArrayList<>();
    private BrowserTaskRegistry tasks;
    private boolean collectorPauses;
    private Map<String, Object> collected = FOUND;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", 7L);
        // Generous budgets keep this about plan state: a resumed request starts its own step counters.
        properties.setMaxPlanningSteps(12);
        properties.setMaxSteps(12);
        tasks = new BrowserTaskRegistry();
    }

    @Test
    void aResumedPlanContinuesAtTheStepAfterTheBrowserCallExactlyAsAnUninterruptedRun() {
        Run straight = straight(new FollowingModel(List.of(SEARCH_PLAN)));
        Run resumed = paused(new FollowingModel(List.of(SEARCH_PLAN)));

        assertEquals(List.of("rank_jobs_for_resume"), resumed.seen().get(4).tools(),
                "after the collector's report only the next plan step is offered, not every tool and a new plan");
        assertEquals("4", resumed.seen().get(4).step());
        assertSame(straight, resumed);
        assertEquals(4, progress(resumed).cursor());
        assertEquals(0, progress(resumed).replans());
        assertEquals(AgentLoop.State.COMPLETED, resumed.outcome().state());
        assertEquals(List.of("get_resume", "get_job_search_preferences", "collect_boss_jobs", "rank_jobs_for_resume"),
                executed);
    }

    @Test
    void replansUsedBeforeThePauseStillCountAfterIt() {
        collected = NOTHING_FOUND;
        List<List<String>> plans = List.of(
                List.of("get_resume", "lookup"),
                List.of("get_job_search_preferences", "collect_boss_jobs", "rank_jobs_for_resume"),
                List.of("lookup"),
                List.of("lookup"));
        Run straight = straight(new FollowingModel(plans));
        Run resumed = paused(new FollowingModel(plans));

        Observation afterReport = resumed.seen().get(6);
        assertEquals(List.of(AgentPlan.TOOL), afterReport.tools(), "an empty collection still needs a replan after the pause");
        assertEquals("1", afterReport.replansLeft(), "the replan made before the pause is not given back");
        assertSame(straight, resumed);
        assertEquals(AgentLoop.State.REPLAN_LIMIT, resumed.outcome().state(),
                "the fourth plan is the third replan and must hit the limit of two");
        assertEquals(2, progress(resumed).replans());
    }

    @Test
    void aPlanResubmittedAfterThePauseIsRefusedLikeOneSentMidPlan() {
        FollowingModel uninterrupted = new FollowingModel(List.of(SEARCH_PLAN));
        uninterrupted.resubmitAfterCollecting = true;
        FollowingModel interrupted = new FollowingModel(List.of(SEARCH_PLAN));
        interrupted.resubmitAfterCollecting = true;
        Run straight = straight(uninterrupted);
        Run resumed = paused(interrupted);

        JsonNode refusal = resultOf(resumed.outcome().transcript(), "resubmitted");
        assertEquals("AGENT_PLAN_INVALID", refusal.path("error").path("code").asText());
        assertEquals(List.of("rank_jobs_for_resume"), resumed.seen().get(5).tools(),
                "the refused plan leaves the original plan and its cursor in place");
        assertSame(straight, resumed);
        assertEquals(AgentLoop.State.COMPLETED, resumed.outcome().state());
        assertEquals(List.of("get_resume", "get_job_search_preferences", "collect_boss_jobs", "rank_jobs_for_resume"),
                executed);
    }

    private void assertSame(Run straight, Run resumed) {
        assertEquals(straight.seen(), resumed.seen(), "every model call is offered the same tools at the same plan step");
        assertEquals(progress(straight), progress(resumed), "same plan, cursor, pending replan and replans used");
        assertEquals(straight.outcome().state(), resumed.outcome().state());
        assertEquals(straight.outcome().transcript().getLast().content(), resumed.outcome().transcript().getLast().content());
    }

    private Run straight(FollowingModel model) {
        collectorPauses = false;
        executed.clear();
        AgentLoop.Outcome outcome = loop(model).run(new TranscriptPolicy(properties).normalize(
                        List.of(AgentMessage.user(QUESTION)), null), null, context, new RecordingSink(), () -> false,
                clock.get(), new AgentLoop.TurnOptions(null, List.of(), false));
        return new Run(model.seen, outcome);
    }

    private Run paused(FollowingModel model) {
        collectorPauses = true;
        executed.clear();
        RecordingSink sink = new RecordingSink();
        AgentLoop.Outcome first = loop(model).run(new TranscriptPolicy(properties).normalize(
                        List.of(AgentMessage.user(QUESTION)), null), null, context, sink, () -> false,
                clock.get(), new AgentLoop.TurnOptions(null, List.of(), false));
        assertEquals(AgentLoop.State.AWAITING_BROWSER, first.state());
        AgentLoop.BrowserTaskEvent task = (AgentLoop.BrowserTaskEvent) sink.events.stream()
                .filter(event -> event.name().equals("browser_task_required")).findFirst().orElseThrow().payload();
        clock.addAndGet(30_000);
        var report = new TranscriptPolicy.BrowserReport(task.toolCallId(), task.taskId(), "completed",
                mapper.createObjectNode().put("type", "result"));
        AgentLoop.Outcome outcome = loop(model).run(new TranscriptPolicy(properties).normalize(
                        first.transcript(), task.toolCallId(), call -> "unused"), null, context, new RecordingSink(), () -> false,
                clock.get(), new AgentLoop.TurnOptions(null, List.of(), false, report));
        return new Run(model.seen, outcome);
    }

    private PlanProgress progress(Run run) {
        return PlanProgress.of(run.outcome().transcript(), mapper, registry());
    }

    private AgentLoop loop(AgentModel model) {
        return new AgentLoop(model, registry(), mapper, properties, clock::get, tasks);
    }

    private AgentToolRegistry registry() {
        return new AgentToolRegistry(List.of(
                tool("get_resume", ToolSchema.object().integer("resumeId", "resume", true, 1, 100),
                        Map.of("resumeId", 1, "title", "Java 简历", "text", "Java Spring Boot")),
                tool("get_job_search_preferences", ToolSchema.object(), Map.of("configured", true)),
                tool("rank_jobs_for_resume", ToolSchema.object().integer("resumeId", "resume", true, 1, 100),
                        Map.of("matches", List.of(Map.of("jobId", 42, "title", "Java 后端")))),
                tool("lookup", ToolSchema.object(), Map.of("items", List.of())),
                new Collector()));
    }

    private AgentTool tool(String name, ToolSchema schema, Object result) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ToolSchema parameters() { return schema; }
            @Override public Object execute(JsonNode arguments, AgentToolContext context) {
                executed.add(name);
                return result;
            }
        };
    }

    /** Collects on the server when the run goes straight through, in the browser when it pauses; same result. */
    private final class Collector implements DeferrableTool {
        @Override public String name() { return "collect_boss_jobs"; }
        @Override public String description() { return "collect"; }
        @Override public ToolSchema parameters() {
            return ToolSchema.object().string("keyword", "keyword", true, 50).string("city", "city", true, 10)
                    .integer("maxPages", "pages", false, 1, 3);
        }
        @Override public Begin begin(JsonNode arguments, AgentToolContext context) {
            if (collectorPauses) return new InBrowser("browser-extension", "collectBossJobs", Map.of("keyword", "Java 后端"));
            executed.add(name());
            return new Done(collected);
        }
        @Override public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
            executed.add(name());
            return collected;
        }
    }

    /**
     * Decides only from what it is shown, so both runs get the same replies for the same inputs: it
     * calls the one offered plan step, replans after a failed or empty step while plans remain, and
     * otherwise answers. With {@code resubmitAfterCollecting} it tries one plan mid-way instead.
     */
    private final class FollowingModel implements AgentModel {
        private final List<List<String>> plans;
        private final List<Observation> seen = new ArrayList<>();
        boolean resubmitAfterCollecting;

        FollowingModel(List<List<String>> plans) {
            this.plans = plans;
        }

        @Override
        public Reply next(String system, List<AgentMessage> transcript, List<ToolDefinition> definitions, Duration timeout) {
            List<String> tools = definitions.stream().map(ToolDefinition::name).toList();
            seen.add(new Observation(tools, find(system, "当前计划步骤 (\\d+)"), find(system, "剩余重规划次数：(\\d+)")));
            List<AgentMessage> turn = transcript.subList(lastUser(transcript) + 1, transcript.size());
            List<String> calls = turn.stream().filter(AgentMessage::hasToolCalls)
                    .flatMap(message -> message.toolCalls().stream()).map(AgentMessage.ToolCall::name).toList();
            int submitted = (int) calls.stream().filter(AgentPlan.TOOL::equals).count();
            if (submitted == 0) return plan("plan-0", plans.getFirst());
            if (resubmitAfterCollecting && calls.contains("collect_boss_jobs")
                    && !calls.contains("rank_jobs_for_resume") && hasResult(turn, "collect_boss_jobs")
                    && turn.stream().noneMatch(message -> "resubmitted".equals(message.toolCallId()))) {
                return plan("resubmitted", List.of("lookup"));
            }
            List<String> steps = tools.stream().filter(tool -> !AgentPlan.TOOL.equals(tool)).toList();
            if (steps.size() == 1) return Reply.call(call(steps.getFirst()));
            if (submitted < plans.size() && lastStepFailed(turn)) return plan("plan-" + submitted, plans.get(submitted));
            return Reply.answer("已按计划完成：" + calls.size() + " 次调用");
        }

        private Reply plan(String id, List<String> steps) {
            var arguments = mapper.createObjectNode();
            steps.forEach(tool -> arguments.withArray("steps").addObject().put("tool", tool).put("purpose", "按计划执行"));
            return Reply.call(AgentMessage.ToolCall.of(id, AgentPlan.TOOL, arguments.toString()));
        }

        private AgentMessage.ToolCall call(String tool) {
            String arguments = switch (tool) {
                case "get_resume", "rank_jobs_for_resume" -> "{\"resumeId\":1}";
                case "collect_boss_jobs" -> "{\"keyword\":\"Java 后端\",\"city\":\"深圳\",\"maxPages\":1}";
                default -> "{}";
            };
            return AgentMessage.ToolCall.of(tool, tool, arguments);
        }

        private boolean lastStepFailed(List<AgentMessage> turn) {
            for (int index = turn.size() - 1; index >= 0; index--) {
                AgentMessage message = turn.get(index);
                if (!AgentMessage.TOOL.equals(message.role()) || message.toolCallId().startsWith("plan-")) continue;
                JsonNode result = json(message.content());
                JsonNode data = result.path("untrusted_data");
                return !result.path("ok").asBoolean() || data.path("items").isEmpty() && data.path("jobIds").isEmpty();
            }
            return false;
        }
    }

    private boolean hasResult(List<AgentMessage> turn, String callId) {
        return turn.stream().anyMatch(message -> callId.equals(message.toolCallId()));
    }

    private JsonNode resultOf(List<AgentMessage> transcript, String callId) {
        return json(transcript.stream().filter(message -> callId.equals(message.toolCallId()))
                .reduce((first, second) -> second).orElseThrow().content());
    }

    private JsonNode json(String content) {
        try {
            return mapper.readTree(content);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String find(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "-";
    }

    private static int lastUser(List<AgentMessage> transcript) {
        for (int index = transcript.size() - 1; index >= 0; index--) {
            if (AgentMessage.USER.equals(transcript.get(index).role())) return index;
        }
        throw new IllegalStateException("no user message");
    }

    private record Observation(List<String> tools, String step, String replansLeft) {
    }

    private record Run(List<Observation> seen, AgentLoop.Outcome outcome) {
    }
}
