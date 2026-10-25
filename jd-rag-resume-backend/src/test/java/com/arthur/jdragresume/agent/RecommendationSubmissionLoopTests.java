package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.SubmitRecommendationsTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
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
        when(jobs.findById(7L)).thenReturn(job(7, "FULL", "Java 后端实习",
                "负责 Java 后端开发。实习至少 3 个月，每周 5 天到岗"));
        when(jobs.findById(8L)).thenReturn(job(8, "LISTING", "AI 应用实习", "列表摘要：AI 应用开发"));
    }

    private JobDescriptionResponse job(long id, String depth, String title, String description) {
        return new JobDescriptionResponse(id, 1L, title, "示例公司", "深圳", "实习", "在校生",
                description, "熟悉 Redis", "BOSS", "https://www.zhipin.com/job_detail/abc.html", "abc",
                null, null, depth, null, null);
    }

    private AgentTool fake(String name, Object result, boolean fail) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public ToolSchema parameters() { return ToolSchema.object().bool("fail", "模拟采集失败", false).bool("empty", "模拟空结果", false); }
            @Override public Object execute(JsonNode args, AgentToolContext context) {
                if (fail || args.path("fail").asBoolean(false)) throw new BusinessException("BOSS_PLUGIN_UNAVAILABLE", "插件未登录");
                if (args.path("empty").asBoolean(false)) return Map.of("pagesRead", 0, "jobIds", List.of());
                return result;
            }
        };
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
                new AgentToolRegistry(List.of(read, new SubmitRecommendationsTool(jobs, preferences),
                        fake("collect_boss_jobs", Map.of("pagesRead", 1, "jobIds", List.of(7, 8)), false),
                        fake("rank_jobs_for_resume", Map.of("items", List.of(7, 8)), false),
                        fake("list_resumes", Map.of("items", List.of("Java 版本", "AI 版本")), false))),
                json, properties, System::currentTimeMillis);
    }

    private AgentMessage.ToolCall call(String id, String name, String args) {
        return AgentMessage.ToolCall.of(id, name, args);
    }

    private String item(long id, String evidence) {
        return "{\"jobId\":" + id + ",\"reason\":\"项目技术栈相符\",\"evidence\":\"" + evidence + "\"}";
    }

    private String proposal(String... items) {
        return "{\"recommendations\":[" + String.join(",", items) + "]}";
    }

    private AgentLoop.Outcome run(String question, AgentModel model, List<String> messages) {
        var input = new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user(question)), null);
        return loop(model).run(input, null, context, (event, value) -> {
            if ("message".equals(event)) messages.add(((Map<?, ?>) value).get("content").toString());
        }, () -> false);
    }

    private AgentLoop.Outcome run(AgentModel model, List<String> messages) {
        return run("帮我找深圳 Java 实习", model, messages);
    }

    @Test
    void freeFormAnswerAfterCollectionKeepsExplanationWithStatusPrefix() {
        AtomicInteger calls = new AtomicInteger();
        String explanation = "搜索覆盖了深圳的一页岗位，但还没有读取完整 JD，下一步要核实。";
        AgentModel model = (system, transcript, tools, timeout) -> calls.getAndIncrement() == 0
                ? AgentModel.Reply.call(call("collect", "collect_boss_jobs", "{}"))
                : AgentModel.Reply.answer(explanation);
        List<String> outputs = new ArrayList<>();
        assertEquals(AgentLoop.State.COMPLETED, run(model, outputs).state());
        assertTrue(outputs.getLast().startsWith("状态：尚未经推荐验收"));
        assertTrue(outputs.getLast().endsWith(explanation));
        assertTrue(outputs.getLast().contains("已采集") || outputs.getLast().contains("采集候选"));
    }

    @Test
    void emptySuccessfulCollectionDoesNotPrefixTheExplanation() {
        AtomicInteger calls = new AtomicInteger();
        String explanation = "本轮没有采集到岗位，请换个关键词再试。";
        List<String> outputs = new ArrayList<>();
        run((system, transcript, tools, timeout) -> calls.getAndIncrement() == 0
                ? AgentModel.Reply.call(call("collect", "collect_boss_jobs", "{\\"empty\\":true}"))
                : AgentModel.Reply.answer(explanation), outputs);
        assertEquals(explanation, outputs.getLast());
    }

    @Test
    void internshipInterviewQuestionPreservesPlainAnswer() {
        String explanation = "一般会问 Java 基础、项目中的并发处理。";
        List<String> outputs = new ArrayList<>();
        run("实习面试一般问什么", (system, transcript, tools, timeout) ->
                AgentModel.Reply.answer(explanation), outputs);
        assertEquals(explanation, outputs.getLast());
    }

    @Test
    void resumeDirectionSelectionPreservesTheModelAnswer() {
        AtomicInteger calls = new AtomicInteger();
        String explanation = "你有 Java 和 AI 两份简历，想用哪份找岗位？";
        List<String> outputs = new ArrayList<>();
        run("帮我找岗位", (system, transcript, tools, timeout) -> calls.getAndIncrement() == 0
                ? AgentModel.Reply.call(call("list", "list_resumes", "{}"))
                : AgentModel.Reply.answer(explanation), outputs);
        assertEquals(explanation, outputs.getLast());
    }

    @Test
    void collectionFailureKeepsTheActualLoginExplanationWithoutStatusPrefix() {
        AtomicInteger calls = new AtomicInteger();
        String explanation = "浏览器插件没登录，采集没成功。请先登录 BOSS，再试一次。";
        List<String> outputs = new ArrayList<>();
        run((system, transcript, tools, timeout) -> calls.getAndIncrement() == 0
                ? AgentModel.Reply.call(call("collect", "collect_boss_jobs", "{\"fail\":true}"))
                : AgentModel.Reply.answer(explanation), outputs);
        assertEquals(explanation, outputs.getLast());
    }

    @Test
    void validSubmissionFinishesWithNoJobIdInUserText() {
        AgentModel model = (system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("submit", "submit_recommendations",
                        proposal(item(7, "负责 Java 后端开发"))));
        List<String> outputs = new ArrayList<>();
        assertEquals(AgentLoop.State.COMPLETED, run(model, outputs).state());
        assertTrue(outputs.getLast().contains("示例公司"));
        assertTrue(outputs.getLast().contains("JD 原文证据"));
        assertFalse(outputs.getLast().contains("jobId="));
    }

    @Test
    void oneRejectedItemCanBeResubmittedWithoutRepeatingTheApprovedOne() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(jobs.findById(8L)).thenReturn(job(8, "FULL", "AI 应用实习", "负责大模型应用开发与 RAG 检索"));
        String first = proposal(item(7, "负责 Java 后端开发"), item(8, "凭空捏造的不存在要求"));
        String second = proposal(item(8, "负责大模型应用开发与 RAG 检索"));
        AgentModel model = (system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("s" + calls.incrementAndGet(), "submit_recommendations",
                        calls.get() == 1 ? first : second));
        List<String> outputs = new ArrayList<>();
        assertEquals(AgentLoop.State.COMPLETED, run(model, outputs).state());
        assertEquals(2, calls.get());
        assertTrue(outputs.getLast().contains("Java 后端实习"));
        assertTrue(outputs.getLast().contains("AI 应用实习"));
        assertFalse(outputs.getLast().contains("待核实岗位"));
        assertFalse(outputs.getLast().contains("jobId="));
    }

    @Test
    void secondFailureStillCompletesWithApprovedAndPendingReasons() {
        when(jobs.findById(8L)).thenReturn(job(8, "FULL", "AI 应用实习", "负责大模型应用开发与 RAG 检索"));
        AtomicInteger calls = new AtomicInteger();
        String first = proposal(item(7, "负责 Java 后端开发"), item(8, "凭空捏造的不存在要求"));
        String second = proposal(item(8, "依旧不存在的岗位描述"));
        AgentModel model = (system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("s" + calls.incrementAndGet(), "submit_recommendations",
                        calls.get() == 1 ? first : second));
        List<String> outputs = new ArrayList<>();
        assertEquals(AgentLoop.State.COMPLETED, run(model, outputs).state());
        assertEquals(2, calls.get());
        assertTrue(outputs.getLast().contains("Java 后端实习"));
        assertTrue(outputs.getLast().contains("待核实岗位"));
        assertTrue(outputs.getLast().contains("引用片段不在"));
        assertFalse(outputs.getLast().contains("jobId="));
    }

    @Test
    void withoutRetryBudgetKeepsSuccessfulEntriesAndReportsPending() {
        properties.setMaxPlanningSteps(1);
        List<String> outputs = new ArrayList<>();
        var result = run((system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("s1", "submit_recommendations", proposal(
                        item(7, "负责 Java 后端开发"), item(8, "列表摘要：AI 应用开发")))), outputs);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertTrue(outputs.getLast().contains("Java 后端实习"));
        assertTrue(outputs.getLast().contains("待核实岗位"));
    }
}
