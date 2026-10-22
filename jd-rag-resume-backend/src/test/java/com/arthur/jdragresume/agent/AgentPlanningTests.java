package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AgentPlanningTests {
    private final AgentProperties properties = new AgentProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger executions = new AtomicInteger();
    private final List<String> events = new ArrayList<>();
    private final List<Object> payloads = new ArrayList<>();
    private Object data = Map.of("jobs", List.of("公司甲"));
    private boolean fails;

    private final AgentTool lookup = new AgentTool() {
        public String name() { return "lookup"; }
        public String description() { return "lookup"; }
        public ToolSchema parameters() { return ToolSchema.object(); }
        public Object execute(JsonNode arguments, AgentToolContext context) {
            executions.incrementAndGet();
            if (fails) throw new IllegalStateException("database unavailable");
            return data;
        }
    };

    @Test
    void simpleQuestionCallsToolsDirectlyWithoutAPlanningCallOrTrace() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> calls.incrementAndGet() == 1
                ? call("lookup") : AgentModel.Reply.answer("找到公司甲"), false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(2, calls.get());
        assertEquals(1, executions.get());
        assertFalse(events.contains("plan"));
        assertNull(done().trace());
    }

    @Test
    void planIsReturnedAndEachExecutionIsRestrictedToItsCurrentTool() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.incrementAndGet()) {
            case 1 -> plan(2);
            case 2, 3 -> {
                assertEquals(List.of("lookup"), tools.stream().map(AgentModel.ToolDefinition::name).toList());
                yield call("lookup");
            }
            default -> {
                assertTrue(tools.isEmpty());
                yield AgentModel.Reply.answer("优先准备公司甲");
            }
        }, true);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(List.of("plan", "step_started", "step", "step_started", "step", "message", "done"), events);
        assertEquals(3, done().trace().size());
    }

    @Test
    void oversizedPlanStopsAfterSixExecutionsAndReturnsPartialDataAndReason() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> tools.isEmpty() ? AgentModel.Reply.answer("已查到公司甲，6 个工具步骤已用完，其他方向尚未核实")
                : calls.incrementAndGet() == 1 ? plan(8) : call("lookup"), true);
        assertEquals(AgentLoop.State.STEP_LIMIT, outcome.state());
        assertEquals(6, executions.get());
        assertFalse(done().partialResults().isEmpty());
        assertTrue(outcome.transcript().getLast().content().contains("公司甲"));
        assertTrue(outcome.transcript().getLast().content().contains("6"));
        assertDoesNotThrow(() -> new TranscriptPolicy(properties).normalize(withQuestion(outcome.transcript()), null));
    }

    @Test
    void aParallelBatchCannotBypassTheConfiguredExecutionLimit() {
        properties.setMaxPlanningSteps(2);
        var outcome = run((system, transcript, tools, timeout) -> AgentModel.Reply.call(
                AgentMessage.ToolCall.of("a", "lookup", "{}"), AgentMessage.ToolCall.of("b", "lookup", "{}"),
                AgentMessage.ToolCall.of("c", "lookup", "{}")), false);
        assertEquals(AgentLoop.State.STEP_LIMIT, outcome.state());
        assertEquals(2, executions.get());
        assertDoesNotThrow(() -> new TranscriptPolicy(properties).normalize(withQuestion(outcome.transcript()), null));
    }

    @Test
    void failedToolAllowsAtMostTwoReplansWithoutResettingTheExecutionBudget() {
        fails = true;
        assertReplanLimit();
    }

    @Test
    void emptyToolDataAlsoAllowsAtMostTwoReplans() {
        data = Map.of("jobs", List.of(), "total", 0);
        assertReplanLimit();
    }

    private void assertReplanLimit() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> calls.incrementAndGet() % 2 == 1 ? plan(1) : call("lookup"), true);
        assertEquals(AgentLoop.State.REPLAN_LIMIT, outcome.state());
        assertEquals(3, executions.get());
        assertEquals(3, events.stream().filter("plan"::equals).count());
        assertTrue(outcome.transcript().getLast().content().contains("重规划上限"));
    }

    @Test
    void successfulStepCannotTriggerAnUnboundedPlanReset() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> calls.incrementAndGet() == 2 ? call("lookup") : plan(2), false);
        assertEquals(AgentLoop.State.FAILED, outcome.state());
        assertEquals(1, executions.get());
    }

    @Test
    void modelCannotExecuteAnUnplannedTool() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> calls.incrementAndGet() == 1 ? plan(1) : call("delete_everything"), false);
        assertEquals(AgentLoop.State.FAILED, outcome.state());
        assertEquals(0, executions.get());
    }

    @Test
    void anUnfinishedPlanCannotBeReportedAsCompleted() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> calls.incrementAndGet() == 1
                ? plan(2) : AgentModel.Reply.answer("计划还没执行但我已经知道答案了"), false);
        assertEquals(AgentLoop.State.FAILED, outcome.state());
        assertEquals(0, executions.get());
        assertTrue(outcome.transcript().getLast().content().contains("未完成计划"));
    }

    @Test
    void malformedPlansFailExplicitly() {
        for (String arguments : List.of("broken", "{}", "{\"steps\":[]}", "{\"steps\":[{\"tool\":\"unknown\",\"purpose\":\"查询\"}]}")) {
            events.clear();
            payloads.clear();
            var outcome = run((system, transcript, tools, timeout) -> AgentModel.Reply.call(
                    AgentMessage.ToolCall.of("p", AgentPlan.TOOL, arguments)), false);
            assertEquals(AgentLoop.State.FAILED, outcome.state());
        }
        assertEquals(0, executions.get());
    }

    private AgentLoop.Outcome run(AgentModel model, boolean trace) {
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(lookup)), mapper, properties, () -> 1000L);
        return loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("这周优先准备哪几家")), null),
                null, new AgentToolContext(new AppUser()), (event, payload) -> { events.add(event); payloads.add(payload); },
                () -> false, 1000L, new AgentLoop.TurnOptions(null, List.of(), trace));
    }

    private AgentLoop.DoneEvent done() {
        return (AgentLoop.DoneEvent) payloads.get(events.lastIndexOf("done"));
    }

    private AgentModel.Reply plan(int count) {
        var steps = mapper.createArrayNode();
        for (int index = 0; index < count; index++) steps.addObject().put("tool", "lookup").put("purpose", "查找准备依据");
        return AgentModel.Reply.call(AgentMessage.ToolCall.of("plan", AgentPlan.TOOL, mapper.createObjectNode().set("steps", steps).toString()));
    }

    private static AgentModel.Reply call(String tool) {
        return AgentModel.Reply.call(AgentMessage.ToolCall.of("call", tool, "{}"));
    }

    private static List<AgentMessage> withQuestion(List<AgentMessage> transcript) {
        List<AgentMessage> result = new ArrayList<>(transcript);
        result.add(AgentMessage.user("继续"));
        return result;
    }
}
