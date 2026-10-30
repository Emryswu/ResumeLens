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

    private final List<String> detailCalls = new ArrayList<>();
    private final AgentTool detail = new AgentTool() {
        public String name() { return "detail"; }
        public String description() { return "detail"; }
        public ToolSchema parameters() { return ToolSchema.object(); }
        public Object execute(JsonNode arguments, AgentToolContext context) {
            detailCalls.add("detail");
            return Map.of("detail", "公司甲详情");
        }
    };

    @Test
    void consecutivePlanStepsCalledTogetherRunInOrder() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.incrementAndGet()) {
            case 1 -> plan("lookup", "detail");
            case 2 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("a", "lookup", "{}"),
                    AgentMessage.ToolCall.of("b", "detail", "{}"));
            default -> AgentModel.Reply.answer("优先准备公司甲");
        }, false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, executions.get());
        assertEquals(1, detailCalls.size());
    }

    @Test
    void aBatchStopsAtTheFirstFailedStepAndClosesTheRest() {
        fails = true;
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.incrementAndGet()) {
            case 1 -> plan("lookup", "detail");
            case 2 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("a", "lookup", "{}"),
                    AgentMessage.ToolCall.of("b", "detail", "{}"));
            default -> AgentModel.Reply.answer("数据库暂时不可用，没有查到");
        }, false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, executions.get());
        assertTrue(detailCalls.isEmpty(), "a step after a failed step must not run");
        assertDoesNotThrow(() -> new TranscriptPolicy(properties).normalize(withQuestion(outcome.transcript()), null));
    }

    @Test
    void anOutOfOrderBatchGetsACorrectionNamingTheExpectedStep() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.incrementAndGet()) {
            case 1 -> plan("lookup", "detail");
            case 2 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("a", "detail", "{}"),
                    AgentMessage.ToolCall.of("b", "lookup", "{}"));
            case 3 -> {
                String correction = transcript.getLast().content();
                assertTrue(correction.contains("AGENT_PLAN_MISMATCH"));
                assertTrue(correction.contains("lookup"), "correction should name the expected step");
                yield call("lookup");
            }
            case 4 -> call("detail");
            default -> AgentModel.Reply.answer("优先准备公司甲");
        }, false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, executions.get());
        assertEquals(1, detailCalls.size());
    }

    @Test
    void aPlanSubmittedTogetherWithToolsGetsACorrection() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.incrementAndGet()) {
            case 1 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("p", AgentPlan.TOOL, planArguments("lookup")),
                    AgentMessage.ToolCall.of("a", "lookup", "{}"));
            case 2 -> plan("lookup");
            case 3 -> call("lookup");
            default -> AgentModel.Reply.answer("优先准备公司甲");
        }, false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, executions.get());
    }

    @Test
    void aMalformedPlanGetsACorrection() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.incrementAndGet()) {
            case 1 -> plan("lookup + detail");
            case 2 -> plan("lookup");
            case 3 -> call("lookup");
            default -> AgentModel.Reply.answer("优先准备公司甲");
        }, false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(1, executions.get());
    }

    @Test
    void protocolCorrectionsAreBounded() {
        AtomicInteger calls = new AtomicInteger();
        var outcome = run((system, transcript, tools, timeout) -> calls.incrementAndGet() == 1 ? plan(1) : call("detail"), false);
        assertEquals(AgentLoop.State.FAILED, outcome.state());
        assertTrue(detailCalls.isEmpty());
        assertEquals(2 + AgentLoop.MAX_PROTOCOL_CORRECTIONS, calls.get());
    }

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
        properties.setMaxPlanningSteps(6);
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
    void successfulJobSearchRankingMayTriggerOneBoundedReplan() {
        properties.setMaxPlanningSteps(12);
        properties.setMaxSteps(12);
        List<String> collectedKeywords = new ArrayList<>();
        AgentTool resume = new AgentTool() {
            public String name() { return "get_resume"; }
            public String description() { return "resume"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("resumeId", "id", true, 1, 100); }
            public Object execute(JsonNode args, AgentToolContext ctx) { return Map.of("resumeId", 1, "text", "Java Spring Boot"); }
        };
        AgentTool preferences = new AgentTool() {
            public String name() { return "get_job_search_preferences"; }
            public String description() { return "preferences"; }
            public ToolSchema parameters() { return ToolSchema.object(); }
            public Object execute(JsonNode args, AgentToolContext ctx) { return Map.of("configured", true); }
        };
        com.arthur.jdragresume.agent.tool.DeferrableTool collector = new com.arthur.jdragresume.agent.tool.DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "collect"; }
            public ToolSchema parameters() { return ToolSchema.object().string("keyword", "keyword", true, 50)
                    .string("city", "city", true, 10).integer("maxPages", "pages", false, 1, 3); }
            public Begin begin(JsonNode args, AgentToolContext ctx) {
                collectedKeywords.add(args.path("keyword").asText());
                return new Done(Map.of("pagesRead", 1, "jobIds", List.of(42),
                        "finishReason", "MAX_PAGES", "status", "COMPLETED", "dailyPagesLeft", 10));
            }
            public Object resume(JsonNode args, JsonNode report, AgentToolContext ctx) { return Map.of(); }
        };
        AgentTool rank = new AgentTool() {
            public String name() { return "rank_jobs_for_resume"; }
            public String description() { return "rank"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("resumeId", "id", true, 1, 100); }
            public Object execute(JsonNode args, AgentToolContext ctx) {
                return Map.of("matches", List.of(Map.of("jobId", 42, "title", "客服实习")));
            }
        };
        AtomicInteger turns = new AtomicInteger();
        AgentModel model = (system, transcript, tools, timeout) -> {
            int turn = turns.incrementAndGet();
            return switch (turn) {
                case 1 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("resume", "get_resume", "{\"resumeId\":1}"));
                case 2 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("preferences", "get_job_search_preferences", "{}"));
                case 3 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("plan1", AgentPlan.TOOL,
                        "{\"steps\":[{\"tool\":\"collect_boss_jobs\",\"purpose\":\"搜索岗位\"},"
                        + "{\"tool\":\"rank_jobs_for_resume\",\"purpose\":\"检查是否对口\"}]}"));
                case 4 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("collect1", "collect_boss_jobs",
                        "{\"keyword\":\"Java 后端\",\"city\":\"深圳\",\"maxPages\":1}"));
                case 5 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("rank", "rank_jobs_for_resume", "{\"resumeId\":1}"));
                case 6 -> {
                    assertTrue(tools.stream().anyMatch(tool -> AgentPlan.TOOL.equals(tool.name())),
                            "successful but irrelevant ranking should expose bounded replanning");
                    yield AgentModel.Reply.call(AgentMessage.ToolCall.of("plan2", AgentPlan.TOOL,
                            "{\"steps\":[{\"tool\":\"collect_boss_jobs\",\"purpose\":\"换关键词补搜\"}]}"));
                }
                case 7 -> AgentModel.Reply.call(AgentMessage.ToolCall.of("collect2", "collect_boss_jobs",
                        "{\"keyword\":\"Java开发\",\"city\":\"深圳\",\"maxPages\":1}"));
                default -> AgentModel.Reply.answer("已经尝试两个不同的关键词");
            };
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(resume, preferences, collector, rank)),
                mapper, properties, () -> 1000L);
        var outcome = loop.run(new TranscriptPolicy(properties).normalize(
                        List.of(AgentMessage.user("帮我找深圳 Java 后端实习")), null),
                null, new AgentToolContext(new AppUser()), (event, payload) -> {},
                () -> false, 1000L, new AgentLoop.TurnOptions(null, List.of(), false));
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(List.of("Java 后端", "Java开发"), collectedKeywords);
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
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(lookup, detail)), mapper, properties, () -> 1000L);
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

    private AgentModel.Reply plan(String... tools) {
        return AgentModel.Reply.call(AgentMessage.ToolCall.of("plan", AgentPlan.TOOL, planArguments(tools)));
    }

    private String planArguments(String... tools) {
        var steps = mapper.createArrayNode();
        for (String tool : tools) steps.addObject().put("tool", tool).put("purpose", "查找准备依据");
        return mapper.createObjectNode().set("steps", steps).toString();
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
