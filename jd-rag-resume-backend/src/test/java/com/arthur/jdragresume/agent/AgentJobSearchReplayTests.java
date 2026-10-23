package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed, offline search-policy replay. Each scenario is a recorded tool-call/result sequence,
 * not an LLM answer: passing these assertions does not measure the semantic accuracy of a model.
 * All tool envelopes are simulated; no BOSS session, browser or external model is contacted.
 */
class AgentJobSearchReplayTests {
    private static final String CASES = "/agent/job-search-replay.json";
    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> replaysFixedSearchTraces() throws Exception {
        JsonNode cases = fixture(CASES).path("scenarios");
        assertTrue(cases.isArray());
        assertTrue(cases.size() >= 5, "keep distinct budget / stop / zero-result scenarios");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode scenario : cases) {
            String id = scenario.path("id").asText();
            tests.add(DynamicTest.dynamicTest(id, () -> check(scenario)));
        }
        return tests.stream();
    }

    /** Exercise the actual AgentLoop tool gate, rather than only checking a prebuilt transcript. */
    @TestFactory
    Stream<DynamicTest> replaysToolCallsThroughTheRealAgentLoop() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode scenario : fixture(CASES).path("scenarios")) {
            tests.add(DynamicTest.dynamicTest(scenario.path("id").asText() + "-agent-loop",
                    () -> runLoop(scenario)));
        }
        return tests.stream();
    }

    private void runLoop(JsonNode scenario) throws Exception {
        JsonNode steps = scenario.path("steps");
        JsonNode next = scenario.path("next");
        boolean nextAllowed = scenario.path("expected").path("allowed").asBoolean();
        AtomicInteger begun = new AtomicInteger();
        DeferrableTool collector = new DeferrableTool() {
            @Override public String name() { return JobSearchWorkflow.TOOL; }
            @Override public String description() { return "replayed offline BOSS result"; }
            @Override public ToolSchema parameters() {
                return ToolSchema.object().string("keyword", "keyword", true, 50)
                        .string("city", "city", true, 10)
                        .string("experience", "experience", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            @Override public Begin begin(JsonNode args, AgentToolContext context) {
                int index = begun.getAndIncrement();
                JsonNode response = index < steps.size() ? steps.get(index) : mapper.createObjectNode();
                return new Done(Map.of(
                        "pagesRead", response.path("pagesRead").asInt(0),
                        "jobIds", mapper.convertValue(response.path("jobIds"), List.class) == null
                                ? List.of() : mapper.convertValue(response.path("jobIds"), List.class),
                        "dailyPagesLeft", response.path("dailyPagesLeft").asInt(10)));
            }
            @Override public Object resume(JsonNode args, JsonNode report, AgentToolContext context) {
                throw new AssertionError("offline replay must never request a browser continuation");
            }
        };
        AgentModel scriptedModel = (system, transcript, tools, timeout) -> {
            long responses = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (responses > steps.size()) return AgentModel.Reply.answer("已按现有结果收尾，未知条件待核实");
            JsonNode planned = responses == steps.size() ? next : steps.get((int) responses);
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("case-" + (responses + 1),
                    JobSearchWorkflow.TOOL, mapper.valueToTree(Map.of(
                            "keyword", planned.path("keyword").asText(),
                            "city", planned.path("city").asText(),
                            "experience", planned.path("experience").asText(),
                            "maxPages", planned.path("maxPages").asInt())).toString()));
        };

        AgentProperties properties = new AgentProperties();
        properties.setMaxSteps(12);
        properties.setMaxPlanningSteps(12);
        var loop = new AgentLoop(scriptedModel, new AgentToolRegistry(List.of(collector)),
                mapper, properties, System::currentTimeMillis);
        var outcome = loop.run(new TranscriptPolicy(properties).normalize(
                        List.of(AgentMessage.user(scenario.path("question").asText())), null),
                null, new AgentToolContext(new AppUser()), (event, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(steps.size() + (nextAllowed ? 1 : 0), begun.get(),
                "policy gate must intercept a prohibited browser request before invoking the collector");
        var progress = JobSearchWorkflow.inspect(outcome.transcript(), mapper);
        assertEquals(steps.size() + 1, progress.attempts().size(),
                "rejected proposals stay visible in the transcript, but must not execute");
        if (!nextAllowed) {
            var results = outcome.transcript().stream()
                    .filter(m -> AgentMessage.TOOL.equals(m.role())).toList();
            JsonNode blocked = mapper.readTree(results.getLast().content());
            assertEquals("JOB_SEARCH_BUDGET", blocked.path("error").path("code").asText(),
                    "the refusal must be visible as a structured tool error to the model");
        }
    }

    private void check(JsonNode scenario) throws Exception {
        List<AgentMessage> transcript = new ArrayList<>();
        transcript.add(AgentMessage.user(scenario.path("question").asText()));
        assertTrue(JobSearchWorkflow.applies(transcript));
        int step = 0;
        for (JsonNode attempt : scenario.path("steps")) {
            assertEquals("在校生", attempt.path("experience").asText(),
                    "internship search must use explicit student experience filter");
            String callId = "replay-" + ++step;
            String args = mapper.writeValueAsString(Map.of(
                    "keyword", attempt.path("keyword").asText(),
                    "city", attempt.path("city").asText(),
                    "experience", attempt.path("experience").asText(),
                    "maxPages", attempt.path("maxPages").asInt()));
            transcript.add(AgentMessage.assistant(null,
                    List.of(AgentMessage.ToolCall.of(callId, JobSearchWorkflow.TOOL, args))));
            var toolData = Map.of(
                    "pagesRead", attempt.path("pagesRead").asInt(),
                    "jobIds", mapper.convertValue(attempt.path("jobIds"), List.class),
                    "dailyPagesLeft", attempt.path("dailyPagesLeft").asInt());
            transcript.add(AgentMessage.tool(callId, mapper.writeValueAsString(
                    Map.of("tool", JobSearchWorkflow.TOOL, "ok", true, "untrusted_data", toolData))));
        }
        var progress = JobSearchWorkflow.inspect(transcript, mapper);
        JsonNode expected = scenario.path("expected");
        assertEquals(step, progress.attempts().size());
        assertEquals(expected.path("requestedPages").asInt(), progress.requestedPages());
        assertEquals(expected.path("pagesRead").asInt(), progress.pagesRead());
        assertEquals(expected.path("uniqueJobs").asInt(), progress.uniqueJobs());

        JsonNode next = scenario.path("next");
        String refusal = JobSearchWorkflow.refuse(progress, next);
        if (expected.path("allowed").asBoolean()) {
            assertNull(refusal, "a zero-result search must permit a different keyword within budget");
        } else {
            assertNotNull(refusal, "unsafe next search must be refused before opening the browser");
            assertTrue(refusal.contains(expected.path("reasonContains").asText()), refusal);
        }
        String instruction = JobSearchWorkflow.guidance(progress);
        assertTrue(instruction.contains("未经完整 JD 核实"));
        assertTrue(instruction.contains("已发起 " + step));
        if (progress.uniqueJobs() == 0) {
            assertTrue(instruction.contains("补搜"), "zero results are not proof that no jobs exist");
        }
    }

    /** Verifies that the saved, redacted source contains internship and non-internship examples. */
    @Test
    void savedBossPayloadContainsBothInternshipAndNonInternshipGroundTruth() throws Exception {
        // Kept in browser-extension once: do not copy sensitive session fields into eval fixtures.
        var file = java.nio.file.Path.of("..", "browser-extension", "tests", "fixtures",
                "boss-joblist.sample.json");
        JsonNode jobs = mapper.readTree(file.toFile()).path("zpData").path("jobList");
        assertTrue(jobs.isArray());
        assertTrue(jobs.size() >= 3);
        assertTrue(jobs.findValues("jobType").stream().anyMatch(type -> type.asInt() == 4));
        assertTrue(jobs.findValues("jobType").stream().anyMatch(type -> type.asInt() != 4));
        assertTrue(jobs.findValues("jobExperience").stream()
                .anyMatch(value -> value.asText().contains("在校")));
    }

    private JsonNode fixture(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return mapper.readTree(input);
        }
    }
}
