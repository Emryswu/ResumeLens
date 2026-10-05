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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline demo must exercise the same loop, tools and confirmation gate as a real model. */
class ScriptedAgentModelTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final List<String> calls = new ArrayList<>();

    @Test
    void plainQuestionWalksThroughReadToolsAndAnswers() {
        AgentLoop.Outcome outcome = run("哪些职位适合我？");

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(List.of("list_resumes", "rank_jobs_for_resume", "search_resume_evidence"), calls);
        String answer = outcome.transcript().get(outcome.transcript().size() - 1).content();
        assertTrue(answer.startsWith("【演示模式"), answer);
        assertTrue(answer.contains("Java 后端"), answer);
    }

    @Test
    void askingForAnAnalysisStopsAtTheConfirmationGate() {
        AgentLoop.Outcome outcome = run("帮我分析最合适的职位");

        assertEquals(AgentLoop.State.AWAITING_CONFIRMATION, outcome.state());
        assertEquals(List.of("list_resumes", "rank_jobs_for_resume", "search_resume_evidence"), calls);
    }

    @Test
    void rejectionIsReportedToTheUserWithoutTheModelFacingInstruction() {
        AgentLoop.Outcome paused = run("帮我分析最合适的职位");
        // Paused turns end with the assistant message whose write call awaits a decision.
        String pendingId = paused.transcript().get(paused.transcript().size() - 1).toolCalls().get(0).id();
        TranscriptPolicy.Approval rejection = new TranscriptPolicy.Approval(pendingId, false);

        AgentLoop.Outcome outcome = loop().run(new TranscriptPolicy(properties).normalize(paused.transcript(), rejection),
                rejection, new AgentToolContext(new AppUser()), (event, payload) -> { }, () -> false);

        String answer = outcome.transcript().get(outcome.transcript().size() - 1).content();
        assertTrue(answer.contains("你拒绝了这次操作"), answer);
        assertTrue(!answer.contains("不要重试"), answer);
    }

    private AgentLoop.Outcome run(String question) {
        return loop().run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user(question)), null),
                null, new AgentToolContext(new AppUser()), (event, payload) -> { }, () -> false);
    }

    private AgentLoop loop() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("list_resumes", false, Map.of("total", 1, "resumes", List.of(Map.of("resumeId", 3, "title", "陈思远简历")))),
                tool("rank_jobs_for_resume", false, Map.of("resumeId", 3, "matches",
                        List.of(Map.of("jobId", 8, "title", "Java 后端", "companyName", "某支付公司", "similarity", 0.81)))),
                tool("search_resume_evidence", false, Map.of("threshold", 0.72, "kept", List.of(Map.of("chunkIndex", 0)), "filteredCount", 2)),
                tool("start_analysis", true, Map.of("analysisId", 1))
        ));
        return new AgentLoop(new ScriptedAgentModel(objectMapper), registry, objectMapper, properties, System::currentTimeMillis);
    }

    private AgentTool tool(String name, boolean write, Object result) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public ToolSchema parameters() {
                return ToolSchema.object()
                        .integer("resumeId", "r", false, 1, Long.MAX_VALUE)
                        .integer("jobId", "j", false, 1, Long.MAX_VALUE)
                        .integer("limit", "l", false, 1, 10);
            }

            @Override
            public boolean requiresConfirmation() {
                return write;
            }

            @Override
            public Object execute(JsonNode arguments, AgentToolContext context) {
                calls.add(name);
                return result;
            }
        };
    }
}
