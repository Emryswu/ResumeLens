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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The turn's tool-step and model-call budgets are spent once per turn, not once per request: a turn
 * that pauses for the browser or a confirmation after every call must see the same remaining steps
 * and stop at the same point as the same turn run straight through.
 */
class AgentStepBudgetResumeTests {
    private static final int MAX_RESUMES = 40;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AppUser user = new AppUser();
    private final AgentToolContext context = new AgentToolContext(user);
    private final AgentProperties properties = new AgentProperties();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<String> executed = new ArrayList<>();
    private BrowserTaskRegistry tasks;
    private Pause pause;
    private boolean writeFails;
    private int lookups;

    enum Pause { NONE, BROWSER, APPROVE, REJECT }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", 7L);
        tasks = new BrowserTaskRegistry();
        properties.setMaxPlanningSteps(6);
    }

    @Test
    void pausingAfterEveryBrowserCallSeesTheSameRemainingStepsAndStopsAtTheSameCall() {
        properties.setMaxSteps(12);
        Run straight = drive(new Model("fetch_page"), Pause.NONE);
        Run paused = drive(new Model("fetch_page"), Pause.BROWSER);

        assertEquals(List.of("-", "5", "4", "3", "2", "1", "0"), straight.seen().stream().map(Seen::remaining).toList());
        assertSameBudget(straight, paused);
        assertEquals(6, paused.resumes(), "every one of the six steps paused once");
    }

    @Test
    void pausingForAConfirmationAfterEveryCallSeesTheSameRemainingStepsAndStopsAtTheSameCall() {
        properties.setMaxSteps(12);
        Run straight = drive(new Model("write_note"), Pause.NONE);
        Run paused = drive(new Model("write_note"), Pause.APPROVE);

        assertSameBudget(straight, paused);
        assertEquals(6, paused.resumes());
    }

    @Test
    void repeatedPausesCannotOutrunTheModelCallLimit() {
        properties.setMaxPlanningSteps(20);
        properties.setMaxSteps(4);
        for (Pause kind : List.of(Pause.BROWSER, Pause.APPROVE)) {
            String tool = kind == Pause.BROWSER ? "fetch_page" : "write_note";
            Run straight = drive(new Model(tool), Pause.NONE);
            Run paused = drive(new Model(tool), kind);

            assertSameBudget(straight, paused);
            assertEquals(4, paused.executions(), kind + ": four model calls, then only the closing answer");
            assertEquals(5, paused.seen().size(), kind + ": the closing answer is the only call past the limit");
            assertTrue(paused.seen().getLast().tools().isEmpty());
        }
    }

    @Test
    void aPausedCallReservesItsStepSoAPlainCallInTheSameBatchCannotTakeTheLastOne() {
        properties.setMaxPlanningSteps(2);
        properties.setMaxSteps(12);
        for (Pause kind : List.of(Pause.BROWSER, Pause.APPROVE)) {
            String tool = kind == Pause.BROWSER ? "fetch_page" : "write_note";
            Run straight = drive(new Model(tool, true), Pause.NONE);
            Run paused = drive(new Model(tool, true), kind);

            // One step left when the model sends [pausing call, lookup]: the pausing call takes it, lookup is refused.
            assertEquals(List.of("lookup", tool), paused.executed(), kind + ": the paused call runs once, the plain call never");
            assertEquals("AGENT_STEP_LIMIT", resultOf(paused, "call-1b").path("error").path("code").asText(), kind.name());
            assertEquals(1, paused.resumes(), kind + ": the turn still pauses for the call that holds the last step");
            assertSameBudget(straight, paused);
        }
    }

    @Test
    void anApprovedConfirmationInsideAPlanContinuesLikeAStepThatNeverPaused() {
        List<List<String>> plans = List.of(List.of("lookup"), List.of("write_note", "lookup"));
        Run straight = drive(new Model(plans), Pause.NONE);
        Run approved = drive(new Model(plans), Pause.APPROVE);

        assertEquals(1, approved.resumes());
        assertEquals(List.of("lookup"), approved.seen().get(4).tools(), "after approval the plan's next step is offered");
        assertEquals(new PlanProgress(progress(approved).plan(), 2, false, 1), progress(approved));
        assertSamePlan(straight, approved);
    }

    @Test
    void aRejectedConfirmationInsideAPlanWaitsForAReplanLikeAFailedStep() {
        List<List<String>> plans = List.of(List.of("lookup"), List.of("write_note", "lookup"));
        writeFails = true;
        Run failed = drive(new Model(plans), Pause.NONE);
        writeFails = false;
        Run rejected = drive(new Model(plans), Pause.REJECT);

        assertEquals(1, rejected.resumes());
        assertEquals(List.of(AgentPlan.TOOL), rejected.seen().get(4).tools(), "a rejected step leaves only a replan open");
        assertEquals("1", rejected.seen().get(4).replansLeft(), "the replan before the confirmation is still spent");
        assertEquals(new PlanProgress(progress(rejected).plan(), 0, true, 1), progress(rejected));
        assertSamePlan(failed, rejected);
    }

    private void assertSameBudget(Run straight, Run paused) {
        assertEquals(straight.seen(), paused.seen(), "same tools and remaining steps on every model call");
        assertEquals(AgentLoop.State.STEP_LIMIT, straight.outcome().state());
        assertEquals(straight.outcome().state(), paused.outcome().state());
        assertEquals(straight.executions(), paused.executions());
        assertEquals(straight.outcome().steps(), paused.outcome().steps(), "model calls are counted for the whole turn");
        assertOneQuestion(paused);
    }

    private void assertSamePlan(Run straight, Run paused) {
        assertEquals(straight.seen(), paused.seen(), "same tools, plan step, remaining replans and steps on every call");
        assertEquals(progress(straight), progress(paused), "same plan, cursor, pending replan and replans used");
        assertEquals(AgentLoop.State.COMPLETED, paused.outcome().state());
        assertEquals(straight.outcome().transcript().getLast().content(), paused.outcome().transcript().getLast().content());
        assertOneQuestion(paused);
    }

    /** PlanProgress and the budgets replay from the last user message: a resume must never write one. */
    private static void assertOneQuestion(Run run) {
        for (List<AgentMessage> transcript : run.transcripts()) {
            assertEquals(1, transcript.stream().filter(message -> AgentMessage.USER.equals(message.role())).count());
        }
    }

    private PlanProgress progress(Run run) {
        return PlanProgress.of(run.outcome().transcript(), mapper, registry());
    }

    private Run drive(Model model, Pause kind) {
        pause = kind;
        executed.clear();
        lookups = 0;
        List<List<AgentMessage>> transcripts = new ArrayList<>();
        RecordingSink sink = new RecordingSink();
        AgentLoop.Outcome outcome = loop(model).run(new TranscriptPolicy(properties).normalize(
                        List.of(AgentMessage.user("逐个处理这些记录")), null), null, context, sink, () -> false,
                clock.get(), new AgentLoop.TurnOptions(null, List.of(), false));
        transcripts.add(outcome.transcript());
        int resumes = 0;
        while (resumes < MAX_RESUMES && (outcome.state() == AgentLoop.State.AWAITING_BROWSER
                || outcome.state() == AgentLoop.State.AWAITING_CONFIRMATION)) {
            resumes++;
            clock.addAndGet(1_000);
            RecordingSink next = new RecordingSink();
            if (outcome.state() == AgentLoop.State.AWAITING_BROWSER) {
                var task = (AgentLoop.BrowserTaskEvent) sink.events.stream()
                        .filter(event -> event.name().equals("browser_task_required")).findFirst().orElseThrow().payload();
                var report = new TranscriptPolicy.BrowserReport(task.toolCallId(), task.taskId(), "completed",
                        mapper.createObjectNode().put("type", "result"));
                outcome = loop(model).run(new TranscriptPolicy(properties).normalize(outcome.transcript(),
                                task.toolCallId(), call -> "unused"), null, context, next, () -> false, clock.get(),
                        new AgentLoop.TurnOptions(null, List.of(), false, report));
            } else {
                var pending = (AgentLoop.PendingConfirmation) sink.events.stream()
                        .filter(event -> event.name().equals("confirmation_required")).findFirst().orElseThrow().payload();
                var approval = new TranscriptPolicy.Approval(pending.toolCallId(), pause == Pause.APPROVE);
                outcome = loop(model).run(new TranscriptPolicy(properties).normalize(outcome.transcript(), approval),
                        approval, context, next, () -> false, clock.get(), new AgentLoop.TurnOptions(null, List.of(), false));
            }
            sink = next;
            transcripts.add(outcome.transcript());
        }
        return new Run(model.seen, outcome, resumes, List.copyOf(executed), transcripts);
    }

    private AgentLoop loop(AgentModel model) {
        return new AgentLoop(model, registry(), mapper, properties, clock::get, tasks);
    }

    private AgentToolRegistry registry() {
        return new AgentToolRegistry(List.of(new Fetch(), new Write(), new Lookup()));
    }

    /** Reads a page on the server, or in the browser when the run pauses; the same result either way. */
    private final class Fetch implements DeferrableTool {
        @Override public String name() { return "fetch_page"; }
        @Override public String description() { return "fetch"; }
        @Override public ToolSchema parameters() { return ToolSchema.object().integer("id", "id", true, 1, 100); }
        @Override public Begin begin(JsonNode arguments, AgentToolContext context) {
            if (pause == Pause.BROWSER) return new InBrowser("browser-extension", "fetchPage", Map.of("id", arguments.path("id").asInt()));
            return new Done(result(arguments));
        }
        @Override public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
            return result(arguments);
        }
        private Object result(JsonNode arguments) {
            executed.add(name());
            return Map.of("items", List.of(arguments.path("id").asInt()));
        }
    }

    /** Asks for confirmation only in the paused runs; the straight run executes (or fails) the same call at once. */
    private final class Write implements AgentTool {
        @Override public String name() { return "write_note"; }
        @Override public String description() { return "write"; }
        @Override public ToolSchema parameters() { return ToolSchema.object().integer("id", "id", false, 1, 100); }
        @Override public boolean requiresConfirmation() { return pause == Pause.APPROVE || pause == Pause.REJECT; }
        @Override public Object preview(JsonNode arguments, AgentToolContext context) { return Map.of("id", 1); }
        @Override public Object execute(JsonNode arguments, AgentToolContext context) {
            if (writeFails) throw new IllegalStateException("write failed");
            executed.add(name());
            return Map.of("saved", List.of(1));
        }
    }

    /** Empty the first time in a run, so the first plan needs a replan; found afterwards. */
    private final class Lookup implements AgentTool {
        @Override public String name() { return "lookup"; }
        @Override public String description() { return "lookup"; }
        @Override public ToolSchema parameters() { return ToolSchema.object(); }
        @Override public Object execute(JsonNode arguments, AgentToolContext context) {
            executed.add(name());
            return Map.of("items", ++lookups == 1 ? List.of() : List.of(1));
        }
    }

    /**
     * Decides only from what it is shown. Without plans it keeps calling {@code tool} while offered;
     * with plans it submits the first, calls the single offered step, replans after a failed or empty
     * step while plans remain, and otherwise answers.
     */
    private final class Model implements AgentModel {
        private final String tool;
        private final List<List<String>> plans;
        private final boolean batch;
        private final List<Seen> seen = new ArrayList<>();

        Model(String tool) { this(tool, false); }
        /** With {@code batch}: one lookup, then {@code tool} and another lookup sent together in one reply. */
        Model(String tool, boolean batch) { this.tool = tool; this.plans = null; this.batch = batch; }
        Model(List<List<String>> plans) { this.tool = null; this.plans = plans; this.batch = false; }

        @Override
        public Reply next(String system, List<AgentMessage> transcript, List<ToolDefinition> definitions, Duration timeout) {
            List<String> tools = definitions.stream().map(ToolDefinition::name).toList();
            List<AgentMessage> turn = transcript.subList(lastUser(transcript) + 1, transcript.size());
            seen.add(new Seen(tools, remaining(turn), find(system, "当前计划步骤 (\\d+)"), find(system, "剩余重规划次数：(\\d+)")));
            long calls = turn.stream().filter(AgentMessage::hasToolCalls).count();
            if (batch) {
                if (tools.isEmpty() || calls > 1) return Reply.answer("已处理到上限");
                return calls == 0 ? Reply.call(AgentMessage.ToolCall.of("call-0", "lookup", "{}"))
                        : Reply.call(AgentMessage.ToolCall.of("call-1a", tool, "{\"id\":1}"),
                                AgentMessage.ToolCall.of("call-1b", "lookup", "{}"));
            }
            if (plans == null) {
                return tools.contains(tool)
                        ? Reply.call(AgentMessage.ToolCall.of("call-" + calls, tool, "{\"id\":" + (calls + 1) + "}"))
                        : Reply.answer("已处理到上限");
            }
            long submitted = turn.stream().filter(AgentMessage::hasToolCalls).flatMap(message -> message.toolCalls().stream())
                    .filter(call -> AgentPlan.TOOL.equals(call.name())).count();
            if (submitted == 0) return plan("plan-0", plans.getFirst());
            List<String> steps = tools.stream().filter(name -> !AgentPlan.TOOL.equals(name)).toList();
            if (steps.size() == 1) return Reply.call(AgentMessage.ToolCall.of("call-" + calls, steps.getFirst(), "{}"));
            if (submitted < plans.size() && lastStepFailed(turn)) return plan("plan-" + submitted, plans.get((int) submitted));
            return Reply.answer("计划处理结束");
        }

        private Reply plan(String id, List<String> steps) {
            var arguments = mapper.createObjectNode();
            steps.forEach(step -> arguments.withArray("steps").addObject().put("tool", step).put("purpose", "按计划执行"));
            return Reply.call(AgentMessage.ToolCall.of(id, AgentPlan.TOOL, arguments.toString()));
        }

        private boolean lastStepFailed(List<AgentMessage> turn) {
            for (int index = turn.size() - 1; index >= 0; index--) {
                AgentMessage message = turn.get(index);
                if (!AgentMessage.TOOL.equals(message.role()) || message.toolCallId().startsWith("plan-")) continue;
                JsonNode result = json(message.content());
                return !result.path("ok").asBoolean() || result.path("untrusted_data").path("items").isEmpty()
                        && result.path("untrusted_data").path("saved").isEmpty();
            }
            return false;
        }
    }

    private String remaining(List<AgentMessage> turn) {
        for (int index = turn.size() - 1; index >= 0; index--) {
            if (AgentMessage.TOOL.equals(turn.get(index).role())) {
                return json(turn.get(index).content()).path("remainingSteps").asText("-");
            }
        }
        return "-";
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

    private record Seen(List<String> tools, String remaining, String step, String replansLeft) {
    }

    private JsonNode resultOf(Run run, String callId) {
        return json(run.outcome().transcript().stream().filter(message -> callId.equals(message.toolCallId()))
                .reduce((first, second) -> second).orElseThrow().content());
    }

    private record Run(List<Seen> seen, AgentLoop.Outcome outcome, int resumes, List<String> executed,
                       List<List<AgentMessage>> transcripts) {
        int executions() { return executed.size(); }
    }
}
