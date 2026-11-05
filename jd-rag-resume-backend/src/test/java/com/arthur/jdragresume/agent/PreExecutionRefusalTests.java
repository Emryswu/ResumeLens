package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class PreExecutionRefusalTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentToolRegistry registry = new AgentToolRegistry(List.of(tool("collect_boss_jobs"), tool("verify_boss_job")));

    @Test
    void conflictCanRetryTheSamePlanStepWithoutUsingAReplanOrToolStep() {
        List<AgentMessage> messages = planned("collect_boss_jobs");
        messages.add(AgentMessage.assistant(null, List.of(call("wrong", "collect_boss_jobs"))));
        messages.add(AgentMessage.tool("wrong", ToolEnvelope.notExecuted("collect_boss_jobs",
                SearchConditionPolicy.CONFLICT, "改为 experience=在校生 后重试")));
        assertEquals(0, TurnUsage.of(messages, mapper).toolSteps());
        assertEquals(new PlanProgress(PlanProgress.of(messages, mapper, registry).plan(), 0, false, 0),
                PlanProgress.of(messages, mapper, registry));

        messages.add(AgentMessage.assistant(null, List.of(call("correct", "collect_boss_jobs"))));
        messages.add(AgentMessage.tool("correct", success("collect_boss_jobs")));
        assertEquals(1, TurnUsage.of(messages, mapper).toolSteps());
        PlanProgress progress = PlanProgress.of(messages, mapper, registry);
        assertEquals(1, progress.cursor());
        assertFalse(progress.needsReplan());
        assertEquals(0, progress.replans());
    }

    @Test
    void refusedSecondActionDoesNotDriftFromPlanProgressAcrossPauseAndResume() {
        for (String code : List.of("ONE_PENDING_ACTION_AT_A_TIME", "ONE_CONFIRMATION_AT_A_TIME")) {
            List<AgentMessage> paused = planned("collect_boss_jobs", "verify_boss_job");
            paused.add(AgentMessage.assistant(null, List.of(call("first", "collect_boss_jobs"),
                    call("refused", "verify_boss_job"))));
            paused.add(AgentMessage.tool("refused", ToolEnvelope.notExecuted("verify_boss_job", code, "等待上一项")));
            assertEquals(1, TurnUsage.of(paused, mapper).toolSteps(), code + ": pending first action counts once");
            assertEquals(0, PlanProgress.of(paused, mapper, registry).cursor());
            assertFalse(PlanProgress.of(paused, mapper, registry).needsReplan());

            paused.add(AgentMessage.tool("first", success("collect_boss_jobs")));
            assertEquals(1, PlanProgress.of(paused, mapper, registry).cursor());
            paused.add(AgentMessage.assistant(null, List.of(call("retry", "verify_boss_job"))));
            paused.add(AgentMessage.tool("retry", success("verify_boss_job")));

            List<AgentMessage> straight = planned("collect_boss_jobs", "verify_boss_job");
            straight.add(AgentMessage.assistant(null, List.of(call("first", "collect_boss_jobs"))));
            straight.add(AgentMessage.tool("first", success("collect_boss_jobs")));
            straight.add(AgentMessage.assistant(null, List.of(call("retry", "verify_boss_job"))));
            straight.add(AgentMessage.tool("retry", success("verify_boss_job")));

            assertEquals(2, TurnUsage.of(paused, mapper).toolSteps(), code);
            assertEquals(TurnUsage.of(straight, mapper).toolSteps(), TurnUsage.of(paused, mapper).toolSteps(), code);
            assertEquals(PlanProgress.of(straight, mapper, registry), PlanProgress.of(paused, mapper, registry), code);
            assertEquals(0, PlanProgress.of(paused, mapper, registry).replans());
        }
    }

    @Test
    void prerequisitesNeedAReplanButStillNeverConsumeAToolStep() {
        List<AgentMessage> messages = planned("collect_boss_jobs");
        messages.add(AgentMessage.assistant(null, List.of(call("missing", "collect_boss_jobs"))));
        messages.add(AgentMessage.tool("missing", ToolEnvelope.notExecuted("collect_boss_jobs",
                "JOB_SEARCH_PREREQUISITES", "先读取简历和求职条件")));
        assertEquals(0, TurnUsage.of(messages, mapper).toolSteps());
        assertTrue(PlanProgress.of(messages, mapper, registry).needsReplan(),
                "prerequisite tools must be added to a new plan, not retried blindly");
    }

    @Test
    void everyStaticRefusalCodeInAgentLoopIsRegistered() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/arthur/jdragresume/agent/AgentLoop.java"))
                .replace("\r\n", "\n");
        Matcher calls = Pattern.compile("\\b(?:rejectBeforeExecution|closeCalls|closeOutstanding)\\(").matcher(source);
        Pattern literal = Pattern.compile("\"([A-Z][A-Z0-9]*_[A-Z0-9_]+)\"");
        int checked = 0;
        while (calls.find()) {
            String args = arguments(source, calls.end());
            Matcher codes = literal.matcher(args);
            while (codes.find()) {
                String code = codes.group(1);
                assertTrue(PreExecutionRefusal.registered(code), "unregistered refusal: " + code);
                checked++;
            }
        }
        assertTrue(checked >= 10, "the scan must inspect the actual call sites");
        assertTrue(PreExecutionRefusal.registered(SearchConditionPolicy.CONFLICT));
        assertTrue(PreExecutionRefusal.registered(JobSearchWorkflow.BUDGET_ERROR));
        assertTrue(PreExecutionRefusal.registered(VerifyPriority.CONFLICT));
        assertTrue(PreExecutionRefusal.registered(com.arthur.jdragresume.agent.tool.ResumeEvidenceValidator.CODE));
        for (PreExecutionRefusal refusal : PreExecutionRefusal.values()) {
            if (refusal.retrySameStep()) {
                assertTrue(TurnUsage.RETRY_SAME_STEP.contains(refusal.code()));
                assertTrue(PlanProgress.NOT_A_STEP.contains(refusal.code()));
                assertFalse(refusal.countsStep());
            }
            if (!refusal.countsStep()) assertTrue(TurnUsage.NOT_STEPPED.contains(refusal.code()));
        }
    }

    private static String arguments(String source, int start) {
        int depth = 1;
        boolean quoted = false;
        for (int i = start; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '"' && (i == 0 || source.charAt(i - 1) != '\\')) quoted = !quoted;
            if (quoted) continue;
            if (ch == '(') depth++;
            if (ch == ')' && --depth == 0) return source.substring(start, i);
        }
        throw new AssertionError("unterminated refusal call");
    }

    private List<AgentMessage> planned(String... tools) {
        StringBuilder json = new StringBuilder("{\"steps\":[");
        for (String tool : tools) {
            if (json.charAt(json.length() - 1) != '[') json.append(',');
            json.append("{\"tool\":\"").append(tool).append("\",\"purpose\":\"执行\"}");
        }
        json.append("]}");
        List<AgentMessage> messages = new ArrayList<>(List.of(
                AgentMessage.user("找深圳实习岗位"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("plan", AgentPlan.TOOL, json.toString()))),
                AgentMessage.tool("plan", "{\"ok\":true,\"planAccepted\":true}")));
        return messages;
    }

    private static AgentMessage.ToolCall call(String id, String name) {
        return AgentMessage.ToolCall.of(id, name, "{}");
    }

    private static String success(String tool) {
        return "{\"tool\":\"" + tool + "\",\"ok\":true,\"untrusted_data\":{\"jobIds\":[42],\"pagesRead\":1}}";
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public ToolSchema parameters() { return ToolSchema.object(); }
            @Override public Object execute(JsonNode arguments, AgentToolContext context) { return "ok"; }
        };
    }
}
