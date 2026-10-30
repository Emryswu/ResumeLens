package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.GetResumeTool;
import com.arthur.jdragresume.agent.tool.ResumeEvidenceValidator;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.SubmitRecommendationsTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.ResumeService;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
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
    private final ResumeService resumes = mock(ResumeService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AgentProperties properties = new AgentProperties();
    private final AgentToolContext context = new AgentToolContext(new AppUser());

    @BeforeEach
    void setUp() {
        when(resumes.findById(1L)).thenReturn(new ResumeResponse(1L, 1L, "owner", "Java 简历",
                null, null, null, null, null, null, null, "熟练使用 Spring Boot、MySQL 构建 REST API，负责服务端接口开发。", null, null));
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
                new AgentToolRegistry(List.of(read, new GetResumeTool(resumes), new SubmitRecommendationsTool(jobs, preferences, resumes),
                        fake("collect_boss_jobs", Map.of("pagesRead", 1, "jobIds", List.of(7, 8)), false),
                        fake("rank_jobs_for_resume", Map.of("items", List.of(7, 8)), false),
                        fake("list_resumes", Map.of("items", List.of("Java 版本", "AI 版本")), false))),
                json, properties, System::currentTimeMillis);
    }

    private AgentMessage.ToolCall call(String id, String name, String args) {
        return AgentMessage.ToolCall.of(id, name, args);
    }

    private String item(long id, String evidence) {
        return "{\"jobId\":" + id + ",\"reason\":\"项目技术栈相符\",\"evidence\":\"" + evidence + "\",\"resumeEvidence\":[{\"quote\":\"熟练使用 Spring Boot、MySQL 构建 REST API\",\"supports\":\"熟悉 Java 后端开发\"}]}";
    }

    private String proposal(String... items) {
        return "{\"resumeId\":1,\"recommendations\":[" + String.join(",", items) + "]}";
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
                ? AgentModel.Reply.call(call("collect", "collect_boss_jobs", "{\"empty\":true}"))
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

    @Test
    void missingEducationShowsProfileHintOnceEvenWithSeveralJobs() {
        when(jobs.findById(7L)).thenReturn(new JobDescriptionResponse(7L, 1L, "Java 后端实习",
                "示例公司", "深圳", "实习", "在校生",
                "负责 Java 后端开发。实习至少 3 个月，每周 5 天到岗",
                "学历要求本科及以上", "BOSS", "https://www.zhipin.com/job_detail/abc.html",
                "abc", null, null, "FULL", null, null));
        when(jobs.findById(9L)).thenReturn(new JobDescriptionResponse(9L, 1L, "Java 服务开发",
                "示例公司", "深圳", "实习", "在校生",
                "负责 Java 系统开发。实习至少 3 个月，每周 5 天到岗",
                "硕士及以上", "BOSS", "https://www.zhipin.com/job_detail/def.html",
                "def", null, null, "FULL", null, null));
        List<String> outputs = new ArrayList<>();
        var result = run((system, transcript, tools, timeout) ->
                AgentModel.Reply.call(call("s", "submit_recommendations", proposal(
                        item(7, "负责 Java 后端开发"), item(9, "负责 Java 系统开发")))), outputs);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        String answer = outputs.getLast();
        String hint = "在求职条件页填写学历/毕业时间后可完成判断";
        assertTrue(answer.contains("JD 门槛说明"));
        assertEquals(1, answer.split(hint, -1).length - 1);
    }

    @Test
    void emptyConversationCanRepairQuoteWithoutSpendingTheRejectedStep() throws Exception {
        properties.setMaxSteps(3);
        AtomicInteger calls = new AtomicInteger();
        String valid = proposal(item(7, "负责 Java 后端开发"));
        String invalid = valid.replace("熟练使用 Spring Boot、MySQL 构建 REST API", "精通从未使用过的 Kubernetes 集群运维");
        AgentModel model = (system, transcript, tools, timeout) -> {
            int step = calls.getAndIncrement();
            if (step == 0) return AgentModel.Reply.call(call("bad", "submit_recommendations", invalid));
            if (step == 1) {
                assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("get_resume")));
                JsonNode rejection;
                try { rejection = json.readTree(transcript.getLast().content()); }
                catch (Exception ex) { throw new AssertionError(ex); }
                assertFalse(rejection.path("executed").asBoolean(true));
                assertEquals(ResumeEvidenceValidator.CODE, rejection.path("error").path("code").asText());
                assertTrue(rejection.path("error").path("message").asText().contains("get_resume({\"resumeId\":1})"));
                return AgentModel.Reply.call(call("read", "get_resume", "{\"resumeId\":1}"));
            }
            assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("submit_recommendations")));
            return AgentModel.Reply.call(call("good", "submit_recommendations", valid));
        };
        List<String> outputs = new ArrayList<>();
        var outcome = run(model, outputs);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(3, calls.get());
        assertEquals(2, TurnUsage.of(outcome.transcript(), json).toolSteps());
        assertFalse(outputs.getLast().contains("步骤上限"));
        assertTrue(outputs.getLast().contains("简历原文证据"));
    }

    @Test
    void mixedSubmissionKeepsApprovedAndPinpointsSecondQuoteOfSecondItem() throws Exception {
        when(jobs.findById(8L)).thenReturn(job(8, "FULL", "AI 应用实习", "负责大模型应用开发与 RAG 检索"));
        var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(item(8, "负责大模型应用开发与 RAG 检索"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) invalid.get("resumeEvidence")).addObject()
                .put("quote", "从未出现在简历中的高并发交易架构设计经验").put("supports", "大模型应用开发");
        AtomicInteger calls = new AtomicInteger();
        List<String> outputs = new ArrayList<>();
        var outcome = run((system, transcript, tools, timeout) -> calls.getAndIncrement() == 0
                ? AgentModel.Reply.call(call("mixed", "submit_recommendations", proposal(item(7, "负责 Java 后端开发"), invalid.toString())))
                : AgentModel.Reply.answer("完成"), outputs);
        JsonNode envelope = outcome.transcript().stream().filter(m -> "mixed".equals(m.toolCallId()))
                .map(m -> { try { return json.readTree(m.content()); } catch (Exception e) { throw new AssertionError(e); } })
                .findFirst().orElseThrow();
        JsonNode data = envelope.path("untrusted_data");
        assertEquals(7, data.path("recommendations").get(0).path("jobId").asInt());
        JsonNode rejected = data.path("rejected").get(0);
        assertEquals(8, rejected.path("jobId").asInt());
        assertTrue(rejected.path("reason").asText().contains("第2条岗位（jobId=8）第2条简历引用"));
        assertFalse(rejected.path("refusal").path("executed").asBoolean(true));
        assertEquals(ResumeEvidenceValidator.CODE, rejected.path("refusal").path("error").path("code").asText());
        verify(jobs, never()).findById(8L);
        assertTrue(outputs.getLast().contains("Java 后端实习"));
        assertFalse(outputs.getLast().contains("jobId="));
    }

    @Test
    void evidenceRepairCanReadResumeAfterThePlanHasCompleted() {
        properties.setMaxPlanningSteps(3);
        AtomicInteger calls = new AtomicInteger();
        String valid = proposal(item(7, "负责 Java 后端开发"));
        String invalid = valid.replace("熟练使用 Spring Boot、MySQL 构建 REST API", "从未出现过的高并发支付平台开发经验");
        List<String> outputs = new ArrayList<>();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.getAndIncrement()) {
            case 0 -> AgentModel.Reply.call(call("plan", "submit_plan",
                    "{\"steps\":[{\"tool\":\"get_job\",\"purpose\":\"读取岗位\"}]}"));
            case 1 -> AgentModel.Reply.call(call("job", "get_job", "{\"jobId\":7}"));
            case 2 -> AgentModel.Reply.call(call("bad", "submit_recommendations", invalid));
            case 3 -> {
                assertTrue(tools.stream().anyMatch(tool -> tool.name().equals("get_resume")));
                yield AgentModel.Reply.call(call("read", "get_resume", "{\"resumeId\":1}"));
            }
            default -> AgentModel.Reply.call(call("good", "submit_recommendations", valid));
        }, outputs);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertEquals(5, calls.get());
        assertEquals(3, TurnUsage.of(outcome.transcript(), json).toolSteps());
        assertEquals(0, TurnUsage.of(outcome.transcript(), json).corrections());
        assertTrue(outputs.getLast().contains("简历原文证据"));
    }

    @Test
    void submissionCannotSwitchAwayFromTheResumeAlreadyReadInTheConversation() {
        AtomicInteger calls = new AtomicInteger();
        List<String> outputs = new ArrayList<>();
        var outcome = run((system, transcript, tools, timeout) -> switch (calls.getAndIncrement()) {
            case 0 -> AgentModel.Reply.call(call("read", "get_resume", "{\"resumeId\":1}"));
            case 1 -> AgentModel.Reply.call(call("wrong", "submit_recommendations",
                    proposal(item(7, "负责 Java 后端开发")).replace("\"resumeId\":1", "\"resumeId\":2")));
            default -> {
                assertTrue(transcript.getLast().content().contains("get_resume"));
                yield AgentModel.Reply.call(call("good", "submit_recommendations", proposal(item(7, "负责 Java 后端开发"))));
            }
        }, outputs);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        verify(resumes, never()).findById(2L);
        assertEquals(2, TurnUsage.of(outcome.transcript(), json).toolSteps());
    }

    @Test
    void freeFormAnswerCannotPublishTheQuoteRejectedBeforeExecution() {
        AtomicInteger calls = new AtomicInteger();
        String invented = "从未出现过的高并发支付平台开发经验";
        List<String> outputs = new ArrayList<>();
        var outcome = run((system, transcript, tools, timeout) -> calls.getAndIncrement() == 0
                ? AgentModel.Reply.call(call("bad", "submit_recommendations",
                        proposal(item(7, "负责 Java 后端开发")).replace("熟练使用 Spring Boot、MySQL 构建 REST API", invented)))
                : AgentModel.Reply.answer("推荐该岗位，因为简历原文写着：" + invented), outputs);
        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertTrue(outputs.getLast().contains("没有通过"));
        assertFalse(outputs.getLast().contains(invented));
    }
}
