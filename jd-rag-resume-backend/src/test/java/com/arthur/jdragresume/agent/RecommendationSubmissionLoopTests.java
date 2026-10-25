package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.SubmitRecommendationsTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationSubmissionLoopTests {
    private final JobDescriptionService jobs = mock(JobDescriptionService.class);
    private final JobSearchPreferencesService preferences = mock(JobSearchPreferencesService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final AgentToolContext context = new AgentToolContext(new AppUser());

    @BeforeEach
    void setUp() {
        when(preferences.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5, null, null, false));
        when(jobs.findById(7L)).thenReturn(new JobDescriptionResponse(7L, 1L,
                "Java 后端实习", "示例公司", "深圳", "实习", "在校生",
                "负责 Java 后端开发。实习至少 3 个月，每周 5 天到岗", "熟悉 Redis",
                "BOSS", "https://www.zhipin.com/job_detail/abc.html", "abc",
                null, null, "FULL", null, null));
    }

    private AgentLoop loop(AgentModel model) {
        AgentTool read = new AgentTool() {
            @Override public String name() { return "get_job"; }
            @Override public String description() { return "read"; }
            @Override public ToolSchema parameters() { return ToolSchema.object().integer("jobId", "id", true, 1, 100); }
            @Override public Object execute(JsonNode args, AgentToolContext context) {
                return Map.of("jobId", 7, "captureDepth", "FULL");
            }
        };
        return new AgentLoop(model,
                new AgentToolRegistry(List.of(read, new SubmitRecommendationsTool(jobs, preferences))),
                json, properties, System::currentTimeMillis);
    }

    private AgentMessage.ToolCall call(String id, String name, String args) {
        return AgentMessage.ToolCall.of(id, name, args);
    }

    private String proposal(String evidence) throws Exception {
        return json.writeValueAsString(Map.of("recommendations", List.of(Map.of("jobId", 7,
                "reason", "项目技术栈相符", "evidence", evidence))));
    }

    private AgentLoop.Outcome run(AgentModel model, List<String> messages) {
        var input = new TranscriptPolicy(properties)
                .normalize(List.of(AgentMessage.user("帮我找深圳 Java 实习")), null);
        return loop(model).run(input, null, context, (event, value) -> {
            if ("message".equals(event)) messages.add(((Map<?, ?>) value).get("content").toString());
        }, () -> false);
    }

    @Test
    void freeFormRecommendationIsBlockedEvenAfterReadingJobData() {
        AtomicInteger calls = new AtomicInteger();
        AgentModel model = (system, transcript, tools, timeout) -> {
            if (calls.getAndIncrement() == 0)
                return AgentModel.Reply.call(call("read", "get_job", "{\"jobId\":7}"));
            return AgentModel.Reply.answer("推荐 7 号 Java 后端岗位，证据我猜的");
        };
        List<String> outputs = new ArrayList<>();
        var result = run(model, outputs);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertFalse(outputs.getLast().contains("证据我猜的"));
        assertTrue(outputs.getLast().contains("没有通过"));
    }

    @Test
    void acceptedSubmissionProducesTheOnlyRecommendationAnswer() throws Exception {
        String args = proposal("负责 Java 后端开发");
        AgentModel model = (system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("submit", "submit_recommendations", args));
        List<String> outputs = new ArrayList<>();
        var result = run(model, outputs);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertTrue(outputs.getLast().contains("示例公司"));
        assertTrue(outputs.getLast().contains("JD 原文证据"));
        assertTrue(outputs.getLast().contains("负责 Java 后端开发"));
    }

    @Test
    void firstRejectionMayBeCorrectedOnlyOnce() throws Exception {
        String bad = proposal("不存在的 JD 证据");
        String valid = proposal("负责 Java 后端开发");
        AtomicInteger calls = new AtomicInteger();
        AgentModel model = (system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("s" + calls.incrementAndGet(), "submit_recommendations",
                        calls.get() == 1 ? bad : valid));
        List<String> outputs = new ArrayList<>();
        var result = run(model, outputs);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertEquals(2, calls.get());
        assertTrue(outputs.getLast().contains("示例公司"));
    }

    @Test
    void secondRejectionStopsWithoutShowingAnyUnvalidatedJob() throws Exception {
        String bad = proposal("不存在的 JD 证据");
        AtomicInteger calls = new AtomicInteger();
        AgentModel model = (system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("s" + calls.incrementAndGet(), "submit_recommendations", bad));
        List<String> outputs = new ArrayList<>();
        var result = run(model, outputs);
        assertEquals(AgentLoop.State.FAILED, result.state());
        assertEquals(2, calls.get());
        assertTrue(outputs.getLast().contains("两次未通过"));
        assertFalse(outputs.getLast().contains("示例公司"));
    }
}
