package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.AgentLoopTests.RecordingSink;
import com.arthur.jdragresume.agent.AgentLoopTests.ScriptedModel;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AWAITING_BROWSER round trip: a deferrable call pauses the turn, the server records the task
 * with a deadline, and only a timely report for exactly that task reaches the tool. Every other
 * ending (late, cancelled, unknown, replayed, tampered, walked away from) becomes an error result
 * the next model call reads, so the model can say what happened.
 */
class AgentBrowserTaskTests {
    private static final String ARGS = "{\"keyword\":\"Java\",\"city\":\"深圳\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppUser user = new AppUser();
    private final AgentToolContext context = new AgentToolContext(user);
    private AgentProperties properties;
    private AtomicLong clock;
    private BrowserTaskRegistry tasks;
    private CollectTool collect;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", 7L);
        properties = new AgentProperties();
        properties.setBrowserTaskTimeoutSeconds(180);
        clock = new AtomicLong(1_000_000);
        tasks = new BrowserTaskRegistry();
        collect = new CollectTool();
    }

    @Test
    void aBrowserCallEndsTheTurnWithATaskAndADeadlineWithoutRunningAnything() {
        RecordingSink sink = new RecordingSink();
        AgentLoop.Outcome outcome = loop(new ScriptedModel(transcript -> AgentModel.Reply.call(call("c1", ARGS))))
                .run(fresh("去 BOSS 找 Java 岗位"), null, context, sink, () -> false);

        assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state());
        assertEquals(List.of("browser_task_required", "done"), sink.names());
        AgentLoop.BrowserTaskEvent event = task(sink);
        assertEquals("c1", event.toolCallId());
        assertEquals("browser-extension", event.executor());
        assertEquals("collectBossJobs", event.action());
        assertEquals(Map.of("keyword", "Java", "city", "深圳"), event.command());
        assertEquals(1_000_000 + 180_000, event.expiresAt());
        assertTrue(event.taskId().matches("bt_[0-9a-f]{32}"), "the extension only accepts [A-Za-z0-9_-]");
        assertEquals(0, collect.resumed.size());
        // The call stays open: it is exactly what the next request has to report on.
        assertTrue(outcome.transcript().stream().noneMatch(message -> AgentMessage.TOOL.equals(message.role())));
    }

    @Test
    void aTimelyReportRunsTheToolOnceWithTheRecordedArguments() {
        Paused paused = pause();
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("采到 2 条"));
        RecordingSink sink = new RecordingSink();
        clock.addAndGet(60_000);

        AgentLoop.Outcome outcome = resume(model, paused, report(paused, "completed"), sink);

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, collect.resumed.size());
        assertEquals("Java", collect.resumed.getFirst().path("keyword").asText());
        assertEquals(List.of("c1"), sink.started());
        JsonNode result = lastToolResult(model);
        assertTrue(result.path("ok").asBoolean());
        assertEquals(2, result.path("untrusted_data").path("saved").asInt());
    }

    @Test
    void aReportAfterTheDeadlineIsRefusedAndTheModelIsToldItExpired() {
        Paused paused = pause();
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("采集超时了"));
        RecordingSink sink = new RecordingSink();
        clock.addAndGet(180_001);

        AgentLoop.Outcome outcome = resume(model, paused, report(paused, "completed"), sink);

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(0, collect.resumed.size(), "a late report never reaches the tool, so nothing is saved");
        JsonNode result = lastToolResult(model);
        assertEquals("BROWSER_TASK_EXPIRED", result.path("error").path("code").asText());
        assertTrue(result.path("error").path("message").asText().contains("180 秒"));
        assertEquals("BROWSER_TASK_EXPIRED", sink.steps().getFirst().errorCode());
        assertEquals("采集超时了", sink.payload("message").get("content"), "the model's explanation is this turn's answer");
    }

    @Test
    void theServerClockDecidesEvenWhenThePageClaimsCompletionJustInTime() {
        Paused paused = pause();
        clock.addAndGet(180_000);
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("ok"));
        resume(model, paused, report(paused, "completed"), new RecordingSink());
        assertEquals(1, collect.resumed.size(), "exactly at the deadline still counts");

        Paused again = pause();
        clock.addAndGet(1);
        ScriptedModel late = new ScriptedModel(transcript -> AgentModel.Reply.answer("ok"));
        resume(late, again, report(again, "expired"), new RecordingSink());
        assertEquals("BROWSER_TASK_EXPIRED", lastToolResult(late).path("error").path("code").asText());
    }

    @Test
    void aCancelledTaskSavesNothingAndTellsTheModelTheUserCancelled() {
        Paused paused = pause();
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("已取消"));

        resume(model, paused, report(paused, "cancelled"), new RecordingSink());

        assertEquals(0, collect.resumed.size());
        JsonNode result = lastToolResult(model);
        assertEquals("BROWSER_TASK_CANCELLED", result.path("error").path("code").asText());
        assertTrue(result.path("error").path("message").asText().contains("不要自动重新发起"));
    }

    @Test
    void aPageThatGaveUpWaitingIsReportedAsExpired() {
        Paused paused = pause();
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("超时"));
        resume(model, paused, report(paused, "expired"), new RecordingSink());
        assertEquals("BROWSER_TASK_EXPIRED", lastToolResult(model).path("error").path("code").asText());
        assertEquals(0, collect.resumed.size());
    }

    @Test
    void aReportCanBeUsedOnceAndAWrongTaskIdLeavesTheRealTaskAlone() {
        Paused paused = pause();
        TranscriptPolicy.BrowserReport forged = new TranscriptPolicy.BrowserReport("c1", "bt_forged", "completed", report());
        ScriptedModel first = new ScriptedModel(transcript -> AgentModel.Reply.answer("?"));
        resume(first, paused, forged, new RecordingSink());
        assertEquals("BROWSER_TASK_UNKNOWN", lastToolResult(first).path("error").path("code").asText());
        assertEquals(0, collect.resumed.size());

        resume(new ScriptedModel(transcript -> AgentModel.Reply.answer("ok")), paused, report(paused, "completed"), new RecordingSink());
        assertEquals(1, collect.resumed.size(), "the genuine report still works after a wrong one");

        ScriptedModel replay = new ScriptedModel(transcript -> AgentModel.Reply.answer("?"));
        resume(replay, paused, report(paused, "completed"), new RecordingSink());
        assertEquals("BROWSER_TASK_UNKNOWN", lastToolResult(replay).path("error").path("code").asText());
        assertEquals(1, collect.resumed.size(), "replaying the same report does not save twice");
    }

    @Test
    void argumentsEditedInTheClientHeldTranscriptDoNotMatchTheIssuedTask() {
        Paused paused = pause();
        List<AgentMessage> tampered = new ArrayList<>(paused.transcript());
        tampered.set(tampered.size() - 1, AgentMessage.assistant(null,
                List.of(call("c1", "{\"keyword\":\"别的关键词\",\"city\":\"北京\"}"))));
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("?"));

        TranscriptPolicy.BrowserReport report = report(paused, "completed");
        loop(model).run(policy().normalize(tampered, "c1", call -> "unused"), null, context, new RecordingSink(), () -> false,
                clock.get(), new AgentLoop.TurnOptions(null, List.of(), false, report));

        assertEquals("BROWSER_TASK_UNKNOWN", lastToolResult(model).path("error").path("code").asText());
        assertEquals(0, collect.resumed.size());
    }

    @Test
    void askingSomethingNewWhileWaitingClosesTheCallAndRefusesItsLateReport() {
        Paused paused = pause();
        List<AgentMessage> next = new ArrayList<>(paused.transcript());
        next.add(AgentMessage.user("算了，换个问题"));
        ScriptedModel model = new ScriptedModel(transcript -> AgentModel.Reply.answer("好的"));

        TranscriptPolicy.Normalized normalized = policy().normalize(next, null, call -> AgentChatService.leftBehind(
                registry(), tasks, 180, user.getId(), call, clock.get()));
        loop(model).run(normalized, null, context, new RecordingSink(), () -> false);

        JsonNode closed = toolResult(model.inputs.getFirst());
        assertEquals("BROWSER_TASK_ABANDONED", closed.path("error").path("code").asText());
        assertEquals(BrowserTaskRegistry.ClaimState.UNKNOWN,
                tasks.claim(user.getId(), paused.taskId(), "c1", ARGS, clock.get()).state(),
                "a report arriving after the user moved on finds no task");
    }

    @Test
    void walkingAwayAfterTheDeadlineIsReportedAsExpiredNotAbandoned() {
        Paused paused = pause();
        clock.addAndGet(180_001);
        String closed = AgentChatService.leftBehind(registry(), tasks, 180, user.getId(), paused.call(), clock.get());
        assertEquals("BROWSER_TASK_EXPIRED", json(closed).path("error").path("code").asText());

        String afterRestart = AgentChatService.leftBehind(registry(), new BrowserTaskRegistry(), 180, user.getId(), paused.call(), clock.get());
        assertEquals("BROWSER_TASK_UNKNOWN", json(afterRestart).path("error").path("code").asText());
    }

    @Test
    void anOpenConfirmationIsStillClosedAsNotConfirmed() {
        AgentLoopTests.WriteTool write = new AgentLoopTests.WriteTool();
        AgentToolRegistry tools = new AgentToolRegistry(List.of(collect, write));
        String closed = AgentChatService.leftBehind(tools, tasks, 180, user.getId(),
                AgentMessage.ToolCall.of("w1", "start_analysis", "{}"), clock.get());
        assertEquals(TranscriptPolicy.DID_NOT_CONFIRM, json(closed).path("error").path("code").asText());
    }

    @Test
    void onlyOnePendingActionPerReply() {
        RecordingSink sink = new RecordingSink();
        AgentLoop.Outcome outcome = loop(new ScriptedModel(transcript -> AgentModel.Reply.call(
                call("c1", ARGS), call("c2", "{\"keyword\":\"Go\",\"city\":\"北京\"}"))))
                .run(fresh("采两次"), null, context, sink, () -> false);

        assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state());
        assertEquals("c1", task(sink).toolCallId());
        assertEquals("ONE_PENDING_ACTION_AT_A_TIME", sink.steps().getFirst().errorCode());
        assertEquals("c2", sink.steps().getFirst().toolCallId());
    }

    @Test
    void aCollectorThatFinishesOnTheServerDoesNotPause() {
        collect.finishesHere = true;
        RecordingSink sink = new RecordingSink();
        AgentLoop.Outcome outcome = loop(new ScriptedModel(
                transcript -> AgentModel.Reply.call(call("c1", ARGS)),
                transcript -> AgentModel.Reply.answer("好了")))
                .run(fresh("采集"), null, context, sink, () -> false);

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(List.of("step_started", "step", "message", "done"), sink.names());
        assertTrue(sink.steps().getFirst().ok());
    }

    @Test
    void invalidArgumentsGoBackToTheModelBeforeAnyTaskIsIssued() {
        RecordingSink sink = new RecordingSink();
        loop(new ScriptedModel(
                transcript -> AgentModel.Reply.call(call("c1", "{\"keyword\":\"Java\"}")),
                transcript -> AgentModel.Reply.answer("缺城市")))
                .run(fresh("采集"), null, context, sink, () -> false);

        assertEquals("INVALID_ARGUMENTS", sink.steps().getFirst().errorCode());
        assertEquals(BrowserTaskRegistry.ClaimState.UNKNOWN, tasks.release(user.getId(), "c1", "{\"keyword\":\"Java\"}", clock.get()).state());
    }

    private Paused pause() {
        RecordingSink sink = new RecordingSink();
        AgentLoop.Outcome outcome = loop(new ScriptedModel(transcript -> AgentModel.Reply.call(call("c1", ARGS))))
                .run(fresh("去 BOSS 找 Java 岗位"), null, context, sink, () -> false);
        assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state());
        return new Paused(outcome.transcript(), task(sink).taskId(), call("c1", ARGS));
    }

    private AgentLoop.Outcome resume(ScriptedModel model, Paused paused, TranscriptPolicy.BrowserReport report, RecordingSink sink) {
        TranscriptPolicy.Normalized normalized = policy().normalize(paused.transcript(), report.toolCallId(), call -> "unused");
        return loop(model).run(normalized, null, context, sink, () -> false, clock.get(),
                new AgentLoop.TurnOptions(null, List.of(), false, report));
    }

    private TranscriptPolicy.BrowserReport report(Paused paused, String outcome) {
        return new TranscriptPolicy.BrowserReport("c1", paused.taskId(), outcome, report());
    }

    private JsonNode report() {
        return objectMapper.createObjectNode().put("type", "result").put("jobs", 2);
    }

    private TranscriptPolicy.Normalized fresh(String question) {
        return policy().normalize(List.of(AgentMessage.user(question)), null);
    }

    private TranscriptPolicy policy() {
        return new TranscriptPolicy(properties);
    }

    private AgentToolRegistry registry() {
        return new AgentToolRegistry(List.of(collect));
    }

    private AgentLoop loop(AgentModel model) {
        return new AgentLoop(model, registry(), objectMapper, properties, clock::get, tasks);
    }

    private static AgentLoop.BrowserTaskEvent task(RecordingSink sink) {
        return (AgentLoop.BrowserTaskEvent) sink.events.stream()
                .filter(event -> event.name().equals("browser_task_required")).findFirst().orElseThrow().payload();
    }

    private JsonNode lastToolResult(ScriptedModel model) {
        return toolResult(model.inputs.getLast());
    }

    private JsonNode toolResult(List<AgentMessage> input) {
        AgentMessage message = input.stream().filter(item -> AgentMessage.TOOL.equals(item.role()))
                .reduce((first, second) -> second).orElseThrow();
        assertNotNull(message.content());
        return json(message.content());
    }

    private JsonNode json(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static AgentMessage.ToolCall call(String id, String arguments) {
        return AgentMessage.ToolCall.of(id, "collect_boss_jobs", arguments);
    }

    private record Paused(List<AgentMessage> transcript, String taskId, AgentMessage.ToolCall call) {
    }

    /** Hands its work to the browser unless {@code finishesHere}; records every resume. */
    static final class CollectTool implements DeferrableTool {
        final List<JsonNode> resumed = new ArrayList<>();
        boolean finishesHere;

        @Override
        public String name() {
            return "collect_boss_jobs";
        }

        @Override
        public String description() {
            return "test collector";
        }

        @Override
        public ToolSchema parameters() {
            return ToolSchema.object()
                    .string("keyword", "keyword", true, 50)
                    .string("city", "city", true, 10);
        }

        @Override
        public Begin begin(JsonNode arguments, AgentToolContext context) {
            if (finishesHere) {
                return new Done(Map.of("saved", 0));
            }
            return new InBrowser("browser-extension", "collectBossJobs",
                    Map.of("keyword", arguments.get("keyword").asText(), "city", arguments.get("city").asText()));
        }

        @Override
        public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
            resumed.add(arguments);
            assertNull(report.get("ignored"));
            return Map.of("saved", report.path("jobs").asInt());
        }
    }
}
