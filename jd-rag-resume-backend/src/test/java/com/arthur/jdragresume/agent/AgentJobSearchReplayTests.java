package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
