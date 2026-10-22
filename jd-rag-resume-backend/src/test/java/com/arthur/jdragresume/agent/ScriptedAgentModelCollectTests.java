package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline demo selects and reads a resume before collecting, then ranks only the collected jobs. */
class ScriptedAgentModelCollectTests {
    private static final String QUESTION = "去 BOSS 采集深圳的 Java 岗位";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScriptedAgentModel model = new ScriptedAgentModel(objectMapper);

    @Test
    void aCollectionQuestionStartsByReadingTheResumeList() {
        AgentModel.Reply reply = next(List.of(AgentMessage.user(QUESTION)));

        AgentMessage.ToolCall call = reply.toolCalls().getFirst();
        assertEquals("list_resumes", call.name());
    }

    @Test
    void aFailedCollectionIsReportedInsteadOfCarryingOn() {
        List<AgentMessage> transcript = after(ToolEnvelope.error("collect_boss_jobs", "EXTENSION_NOT_FOUND", "没有检测到 ResumeLens 浏览器插件"));

        AgentModel.Reply reply = next(transcript);

        assertFalse(reply.hasToolCalls());
        assertTrue(reply.content().contains("没有检测到 ResumeLens 浏览器插件"), reply.content());
    }

    @Test
    void collectedJobsAreRankedOnTheirOwn() throws Exception {
        List<AgentMessage> transcript = after(ok("collect_boss_jobs",
                "{\"pagesRead\":1,\"collected\":2,\"created\":2,\"alreadyInLibrary\":0,\"jobIds\":[5,6]}"));
        transcript.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("s2", "list_resumes", "{}"))));
        transcript.add(AgentMessage.tool("s2", ok("list_resumes", "{\"resumes\":[{\"resumeId\":3,\"title\":\"简历\"}]}")));
        transcript.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("s3", "get_resume", "{\"resumeId\":3}"))));
        transcript.add(AgentMessage.tool("s3", ok("get_resume", "{\"resumeId\":3,\"title\":\"Java 简历\",\"text\":\"Java Spring Boot\"}")));

        AgentModel.Reply reply = next(transcript);

        AgentMessage.ToolCall call = reply.toolCalls().getFirst();
        assertEquals("rank_jobs_for_resume", call.name());
        assertEquals("[5,6]", objectMapper.readTree(call.arguments()).path("jobIds").toString());
    }

    private List<AgentMessage> after(String collectResult) {
        List<AgentMessage> transcript = new ArrayList<>();
        transcript.add(AgentMessage.user(QUESTION));
        transcript.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("s1", "collect_boss_jobs", "{}"))));
        transcript.add(AgentMessage.tool("s1", collectResult));
        return transcript;
    }

    private String ok(String tool, String data) {
        try {
            JsonNode tree = objectMapper.readTree(data);
            return ToolEnvelope.ok(tool, tree, tree.toString(), 4000);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private AgentModel.Reply next(List<AgentMessage> transcript) {
        return model.next("system", transcript, List.of(), Duration.ofSeconds(1));
    }
}
