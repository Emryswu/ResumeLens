package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JobSearchBlockingErrorsTests {
    private final ObjectMapper mapper = new ObjectMapper();

    static Stream<Arguments> blockingBossToolErrors() {
        return JobSearchWorkflow.BOSS_TOOLS.stream().flatMap(tool ->
                JobSearchStopPolicy.PLATFORM_BLOCKING_ERRORS.stream()
                        .map(code -> Arguments.of(tool, code)));
    }

    @ParameterizedTest(name = "{0} returns {1}")
    @MethodSource("blockingBossToolErrors")
    void anyBlockingErrorHaltsBothCollectionAndVerification(String sourceTool, String code) throws Exception {
        var transcript = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        String arguments = argumentsFor(sourceTool);
        transcript.add(AgentMessage.assistant(null, List.of(
                AgentMessage.ToolCall.of("blocked", sourceTool, arguments))));
        transcript.add(AgentMessage.tool("blocked", mapper.writeValueAsString(
                Map.of("tool", sourceTool, "ok", false, "error", Map.of("code", code, "message", "blocked")))));

        var progress = JobSearchWorkflow.inspect(transcript, mapper);
        assertNotNull(progress.haltReason(), sourceTool + ": " + code);
        for (String nextTool : JobSearchWorkflow.BOSS_TOOLS) {
            assertEquals(progress.haltReason(), JobSearchWorkflow.refuse(
                    progress, nextTool, mapper.readTree(argumentsFor(nextTool))),
                    sourceTool + ": " + code + " must block a later " + nextTool);
        }
    }

    private static String argumentsFor(String tool) {
        return JobSearchWorkflow.VERIFY_TOOL.equals(tool)
                ? "{\"jobId\":42}"
                : "{\"keyword\":\"Java 后端\",\"city\":\"深圳\",\"maxPages\":1}";
    }
}
