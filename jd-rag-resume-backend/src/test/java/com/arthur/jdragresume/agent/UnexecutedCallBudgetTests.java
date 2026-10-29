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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call AgentLoop refused before running it (here: a second browser action in the same reply) never reached
 * the browser, so it must not use up the job-search budget, and retrying it later must be allowed.
 */
class UnexecutedCallBudgetTests {
    private static final String COLLECT_JAVA = "{\"keyword\":\"Java\",\"city\":\"深圳\"}";
    private static final String COLLECT_GO = "{\"keyword\":\"Go\",\"city\":\"北京\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppUser user = new AppUser();
    private final AgentToolContext context = new AgentToolContext(user);
    private AgentProperties properties;
    private AtomicLong clock;
    private BrowserTaskRegistry tasks;
    private BrowserTool collect;
    private BrowserTool verify;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(user, "id", 7L);
        properties = new AgentProperties();
        properties.setBrowserTaskTimeoutSeconds(180);
        clock = new AtomicLong(1_000_000);
        tasks = new BrowserTaskRegistry();
        collect = new BrowserTool(JobSearchWorkflow.TOOL,
                ToolSchema.object().string("keyword", "keyword", true, 50).string("city", "city", true, 10),
                report -> Map.of("pagesRead", 1, "jobIds", List.of(1, 2), "dailyPagesLeft", 10));
        verify = new BrowserTool(JobSearchWorkflow.VERIFY_TOOL,
                ToolSchema.object().integer("jobId", "job", true, 1, Long.MAX_VALUE),
                report -> Map.of("jobId", report.path("jobId").asLong(), "captureDepth", "FULL",
                        "verificationStatus", "SAVED_FULL"));
    }

    @Test
    void aVerificationRefusedForAnotherPendingActionCanBeRetriedAndIsChargedOnce() {
        // This turn first collects jobs 1 and 2 and learns that 10 daily pages are left.
        RecordingSink collecting = new RecordingSink();
        AgentLoop.Outcome collected = loop(new ScriptedModel(transcript -> AgentModel.Reply.call(
                collectCall("c0", COLLECT_JAVA))))
                .run(policy().normalize(prepared("找实习并核实岗位"), null), null, context, collecting, () -> false);
        RecordingSink first = new RecordingSink();
        AgentLoop.Outcome paused = resume(collected.transcript(), "c0", task(collecting), objectMapper.createObjectNode(),
                first, transcript -> AgentModel.Reply.call(verifyCall("v1", 1), verifyCall("v2", 2)));
        assertEquals(AgentLoop.State.AWAITING_BROWSER, paused.state());
        assertEquals("v1", task(first).toolCallId());
        assertEquals("ONE_PENDING_ACTION_AT_A_TIME", errorCode(first, "v2"));

        RecordingSink second = new RecordingSink();
        AgentLoop.Outcome retried = resume(paused.transcript(), "v1", task(first), objectMapper.createObjectNode()
                .put("jobId", 1), second, transcript -> AgentModel.Reply.call(verifyCall("v3", 2)));

        assertEquals(AgentLoop.State.AWAITING_BROWSER, retried.state(), second.steps().toString());
        assertEquals("v3", task(second).toolCallId());
        assertEquals(List.of("c0"), collect.begun);
        assertEquals(List.of("v1", "v3"), verify.begun, "the refused v2 never reached the browser");
        var progress = JobSearchWorkflow.inspect(retried.transcript(), objectMapper);
        assertEquals(List.of(1L, 2L), progress.attemptedIds());
        assertEquals(8, progress.dailyPagesLeft(), "two verifications that ran take two daily pages, the refused one none");
    }

    @Test
    void aCollectionRefusedForAnotherPendingActionCanBeRetriedAndIsChargedOnce() {
        RecordingSink first = new RecordingSink();
        AgentLoop.Outcome paused = loop(new ScriptedModel(transcript -> AgentModel.Reply.call(
                collectCall("c1", COLLECT_JAVA), collectCall("c2", COLLECT_GO))))
                .run(policy().normalize(prepared("去 BOSS 找实习"), null), null, context, first, () -> false);
        assertEquals(AgentLoop.State.AWAITING_BROWSER, paused.state());
        assertEquals("ONE_PENDING_ACTION_AT_A_TIME", errorCode(first, "c2"));

        RecordingSink second = new RecordingSink();
        AgentLoop.Outcome retried = resume(paused.transcript(), "c1", task(first), objectMapper.createObjectNode(),
                second, transcript -> AgentModel.Reply.call(collectCall("c3", COLLECT_GO)));

        assertEquals(AgentLoop.State.AWAITING_BROWSER, retried.state(), second.steps().toString());
        assertEquals("c3", task(second).toolCallId());
        assertEquals(List.of("c1", "c3"), collect.begun, "the refused c2 never reached the browser");
        var progress = JobSearchWorkflow.inspect(retried.transcript(), objectMapper);
        assertEquals(2, progress.attempts().size(), "the refused c2 is not an attempt");
        assertEquals(2, progress.requestedPages());
    }

    /** Second line of defence: a refusal written straight to ToolEnvelope.error would be counted as executed again. */
    @Test
    void everyRefusalInAgentLoopGoesThroughTheSingleExit() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/arthur/jdragresume/agent/AgentLoop.java"));
        assertEquals(1, count(source, "ToolEnvelope.error("), "only executionFailure may build a plain error envelope");
        assertEquals(1, count(source, "ToolEnvelope.notExecuted("), "only rejectBeforeExecution may build a refusal");
        assertTrue(body(source, "executionFailure").contains("ToolEnvelope.error("));
        assertTrue(body(source, "rejectBeforeExecution").contains("ToolEnvelope.notExecuted("));
        // executionFailure only carries a failure caught from a tool that ran, never a code the loop chose itself.
        int callSites = 0;
        for (int at = source.indexOf("executionFailure("); at >= 0; at = source.indexOf("executionFailure(", at + 1)) {
            String arguments = arguments(source, at + "executionFailure(".length());
            if (arguments.startsWith("String ")) continue; // The declaration.
            assertTrue(arguments.matches("\\w+\\.name\\(\\), failure"), "executionFailure(" + arguments + ")");
            callSites++;
        }
        assertTrue(callSites > 0);
        String outsideFailureRecord = source.substring(0, source.indexOf("private record ToolFailure("));
        assertFalse(outsideFailureRecord.contains("new ToolFailure("),
                "a hand-made ToolFailure would let a refusal through executionFailure");
    }

    private static String arguments(String source, int start) {
        int depth = 1;
        for (int at = start; at < source.length(); at++) {
            char c = source.charAt(at);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return source.substring(start, at);
        }
        throw new AssertionError("unbalanced call at " + start);
    }

    private static String errorCode(RecordingSink sink, String callId) {
        return sink.steps().stream().filter(step -> callId.equals(step.toolCallId())).findFirst().orElseThrow().errorCode();
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) found++;
        return found;
    }

    private static String body(String source, String method) {
        Matcher declaration = Pattern.compile("\\s" + method + "\\(String [^)]*\\) \\{").matcher(source);
        assertTrue(declaration.find(), method + " must exist");
        return source.substring(declaration.end(), source.indexOf("\n    }", declaration.end()));
    }

    private AgentLoop.Outcome resume(List<AgentMessage> transcript, String callId, AgentLoop.BrowserTaskEvent task,
                                     JsonNode report, RecordingSink sink,
                                     java.util.function.Function<List<AgentMessage>, AgentModel.Reply> next) {
        var browserReport = new TranscriptPolicy.BrowserReport(callId, task.taskId(), "completed", report);
        var normalized = policy().normalize(transcript, callId, call -> "unused");
        return loop(new ScriptedModel(next)).run(normalized, null, context, sink, () -> false, clock.get(),
                new AgentLoop.TurnOptions(null, List.of(), false, browserReport));
    }

    /** A turn that already read the resume and preferences, as collection requires. */
    private List<AgentMessage> prepared(String question) {
        return new ArrayList<>(List.of(
                AgentMessage.user("先读简历和求职条件"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("pre-resume", "get_resume", "{\"resumeId\":1}"))),
                AgentMessage.tool("pre-resume",
                        "{\"tool\":\"get_resume\",\"ok\":true,\"untrusted_data\":{\"resumeId\":1,\"text\":\"Java\"}}"),
                AgentMessage.assistant(null, List.of(
                        AgentMessage.ToolCall.of("pre-preferences", "get_job_search_preferences", "{}"))),
                AgentMessage.tool("pre-preferences",
                        "{\"tool\":\"get_job_search_preferences\",\"ok\":true,\"untrusted_data\":{\"configured\":true}}"),
                AgentMessage.user(question)));
    }

    private TranscriptPolicy policy() {
        return new TranscriptPolicy(properties);
    }

    private AgentLoop loop(AgentModel model) {
        return new AgentLoop(model, new AgentToolRegistry(List.of(collect, verify)), objectMapper, properties,
                clock::get, tasks);
    }

    private static AgentLoop.BrowserTaskEvent task(RecordingSink sink) {
        return (AgentLoop.BrowserTaskEvent) sink.events.stream()
                .filter(event -> event.name().equals("browser_task_required")).findFirst().orElseThrow().payload();
    }

    private static AgentMessage.ToolCall verifyCall(String id, long jobId) {
        return AgentMessage.ToolCall.of(id, JobSearchWorkflow.VERIFY_TOOL, "{\"jobId\":" + jobId + "}");
    }

    private static AgentMessage.ToolCall collectCall(String id, String arguments) {
        return AgentMessage.ToolCall.of(id, JobSearchWorkflow.TOOL, arguments);
    }

    /** Always hands its work to the browser and records which calls got that far. */
    private static final class BrowserTool implements DeferrableTool {
        final List<String> begun = new ArrayList<>();
        private final String name;
        private final ToolSchema parameters;
        private final java.util.function.Function<JsonNode, Object> resumed;

        BrowserTool(String name, ToolSchema parameters, java.util.function.Function<JsonNode, Object> resumed) {
            this.name = name;
            this.parameters = parameters;
            this.resumed = resumed;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test browser tool";
        }

        @Override
        public ToolSchema parameters() {
            return parameters;
        }

        @Override
        public Begin begin(JsonNode arguments, AgentToolContext context) {
            begun.add(context.toolCallId());
            return new InBrowser("browser-extension", name, Map.of("arguments", arguments.toString()));
        }

        @Override
        public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
            return resumed.apply(report);
        }
    }
}
