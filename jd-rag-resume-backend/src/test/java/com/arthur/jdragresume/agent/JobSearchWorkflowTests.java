package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobSearchWorkflowTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void guidanceUsesExplicitConversationTypeAndNotTheLatestUserMessage() {
        var internship = JobSearchContext.empty().patch(mapper.valueToTree(Map.of(
                "city", "深圳", "keyword", "Java", "jobType", "实习")));
        var fullTime = JobSearchContext.empty().patch(mapper.valueToTree(Map.of("jobType", "全职")));
        List<AgentMessage> transcript = List.of(AgentMessage.user("找深圳 Java 实习"), AgentMessage.user("再找几个"));
        assertTrue(JobSearchWorkflow.guidance(JobSearchWorkflow.inspect(transcript, mapper, internship))
                .contains("experience=在校生"));
        assertTrue(JobSearchWorkflow.guidance(JobSearchWorkflow.inspect(transcript, mapper, fullTime))
                .contains("experience=不限"));
        assertTrue(JobSearchWorkflow.applies(transcript, internship));
        assertFalse(JobSearchWorkflow.applies(List.of(AgentMessage.user("解释一下 Java 的 volatile")), internship),
                "having saved conditions must not enable the workflow for unrelated questions");
        assertFalse(JobSearchWorkflow.applies(List.of(AgentMessage.user("今天天气怎么样")), internship));
        var toolCalled = List.of(AgentMessage.user("再说一下"), AgentMessage.assistant(null,
                List.of(AgentMessage.ToolCall.of("x", "collect_boss_jobs", "{}"))));
        assertTrue(JobSearchWorkflow.applies(toolCalled, internship),
                "a collection called this turn activates the workflow even without a search phrase");
    }

    @Test
    void countsRequestedPagesAndUniqueJobsAcrossBrowserResultsWithoutClaimingVerification() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        add(log, "a", "Java 后端", 2, "{\"pagesRead\":2,\"jobIds\":[1,2],\"dailyPagesLeft\":27}");
        add(log, "b", "Java开发", 1, "{\"pagesRead\":1,\"jobIds\":[2,3],\"dailyPagesLeft\":26}");

        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(2, progress.attempts().size());
        assertEquals(3, progress.requestedPages());
        assertEquals(3, progress.pagesRead());
        assertEquals(3, progress.uniqueJobs());
        assertEquals(26, progress.dailyPagesLeft());
        assertTrue(JobSearchWorkflow.guidance(progress).contains("未经完整 JD 核实"));
        assertNull(JobSearchWorkflow.refuse(progress,
                mapper.readTree("{\"keyword\":\"AI 应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}")));
    }

    @Test
    void rejectsRepeatedSearchAndExcessivePageRequests() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java后端", 3, "{\"pagesRead\":0,\"jobIds\":[]}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"Java后端\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}")).contains("相同"));
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":3}")) == null);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":4}")).contains("最多请求"));
    }

    @Test
    void currentTurnDuplicateUsesTheSameKeywordNormalizationAsHistory() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        add(log, "first", "JAVA  Backend", 1, "{\"pagesRead\":1,\"jobIds\":[11]}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\" java backend \",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}"))
                .contains("相同"));
    }

    @Test
    void enforcesSearchCountAndDailyPageBudget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java后端", 1, "{\"pagesRead\":1,\"jobIds\":[1],\"dailyPagesLeft\":1}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":2}")).contains("每日"));
        add(log, "b", "Java开发", 1, "{\"pagesRead\":1,\"jobIds\":[2]}");
        add(log, "c", "后端开发", 1, "{\"pagesRead\":1,\"jobIds\":[3]}");
        assertTrue(JobSearchWorkflow.refuse(JobSearchWorkflow.inspect(log, mapper),
                mapper.readTree("{\"keyword\":\"AI应用\",\"city\":\"深圳\",\"maxPages\":1}")).contains("次采集上限"));
    }

    @Test
    void aNewQuestionStartsAFreshBudgetAndAnUnfinishedCallAlreadyConsumesOneAttempt() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java后端", 3, "{\"pagesRead\":1,\"jobIds\":[1]}");
        log.add(AgentMessage.user("再找一次北京实习"));
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("b", "collect_boss_jobs",
                "{\"keyword\":\"AI应用\",\"city\":\"北京\",\"maxPages\":2}"))));
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(1, progress.attempts().size());
        assertEquals(2, progress.requestedPages());
        assertEquals(0, progress.pagesRead());
        assertEquals(0, JobSearchWorkflow.inspect(log, mapper, "b").attempts().size());
    }

    @Test
    void agentLoopReallyRejectsTheFourthCollectionBeforeTheToolRuns() throws Exception {
        var properties = new AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        AtomicInteger collections = new AtomicInteger();
        DeferrableTool collector = new DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "test"; }
            public ToolSchema parameters() {
                return ToolSchema.object().string("keyword", "keyword", true, 50)
                        .string("city", "city", true, 10).string("experience", "experience", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(JsonNode arguments, AgentToolContext context) {
                int index = collections.incrementAndGet();
                return new Done(Map.of("pagesRead", 1, "jobIds", List.of(index), "dailyPagesLeft", 20));
            }
            public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) { return Map.of(); }
        };
        AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results == 0) return AgentModel.Reply.call(
                    AgentMessage.ToolCall.of("pre-resume", "get_resume", "{\"resumeId\":1}"));
            if (results == 1) return AgentModel.Reply.call(
                    AgentMessage.ToolCall.of("pre-preferences", "get_job_search_preferences", "{}"));
            if (results == 6) return AgentModel.Reply.answer("已达到预算，按已有候选说明不足");
            String keyword = switch ((int) results) {
                case 2 -> "Java后端";
                case 3 -> "Java开发";
                case 4 -> "AI应用";
                default -> "Spring Boot";
            };
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("c" + (results + 1), "collect_boss_jobs",
                    "{\"keyword\":\"" + keyword + "\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}"));
        };
        AgentTool resume = new AgentTool() {
            public String name() { return "get_resume"; }
            public String description() { return "read"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("resumeId", "resume", true, 1, 10); }
            public Object execute(JsonNode args, AgentToolContext ctx) { return Map.of("resumeId", 1, "text", "Java"); }
        };
        AgentTool preferences = new AgentTool() {
            public String name() { return "get_job_search_preferences"; }
            public String description() { return "read"; }
            public ToolSchema parameters() { return ToolSchema.object(); }
            public Object execute(JsonNode args, AgentToolContext ctx) { return Map.of("configured", true); }
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(collector, resume, preferences)), mapper, properties,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertEquals(3, collections.get());
        var failure = mapper.valueToTree(result.transcript().stream()
                .filter(m -> AgentMessage.TOOL.equals(m.role())).toList()).path(5).path("content").asText();
        assertEquals("JOB_SEARCH_BUDGET", mapper.readTree(failure).path("error").path("code").asText());
    }

    @Test
    void verificationsShareTheBudgetAndRefusedCallsDoNotUseItUp() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[1,2,3,4,5,6,7],\"dailyPagesLeft\":10}");
        for (int i = 1; i <= 4; i++) verify(log, "v" + i, i, "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":" + i + ",\"captureDepth\":\"FULL\",\"verificationStatus\":\"SAVED_FULL\"}}");
        verify(log, "dup", 1, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"executed\":false,\"error\":{\"code\":\"JOB_SEARCH_BUDGET\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(1L, 2L, 3L, 4L), progress.verifiedIds(), "a refused call is not counted");
        assertEquals(6, progress.dailyPagesLeft(), "each verification takes one daily page");
        assertTrue(JobSearchWorkflow.refuse(progress, "verify_boss_job", mapper.readTree("{\"jobId\":2}")).contains("已尝试或完成核实"));
        assertNull(JobSearchWorkflow.refuse(progress, "verify_boss_job", mapper.readTree("{\"jobId\":5}")));
        verify(log, "v5", 5, "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":5,\"captureDepth\":\"FULL\",\"verificationStatus\":\"SAVED_FULL\"}}");
        assertTrue(JobSearchWorkflow.refuse(JobSearchWorkflow.inspect(log, mapper), "verify_boss_job",
                mapper.readTree("{\"jobId\":6}")).contains("核实上限"));
        assertTrue(JobSearchWorkflow.guidance(JobSearchWorkflow.inspect(log, mapper)).contains("已核实完整 JD 5 个"));
    }

    @Test
    void failedAndPendingVerificationSpendAttemptsWithoutClaimingFullJd() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        verify(log, "failed", 1, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"error\":{\"code\":\"BOSS_DETAIL_UNAVAILABLE\"}}");
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("pending", "verify_boss_job", "{\"jobId\":2}"))));
        var pending = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(1L, 2L), pending.attemptedIds());
        assertTrue(pending.verifiedIds().isEmpty());
        assertTrue(JobSearchWorkflow.guidance(pending).contains("已核实完整 JD 0 个"));
        log.add(AgentMessage.tool("pending", "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":2,\"captureDepth\":\"FULL\",\"verificationStatus\":\"SAVED_FULL\"}}"));
        var completed = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(1L, 2L), completed.attemptedIds());
        assertEquals(List.of(2L), completed.verifiedIds());
    }

    @Test
    void alreadyFullIsVerifiedWithoutSpendingAttemptBudget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        verify(log, "full", 17, "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":17,\"captureDepth\":\"FULL\",\"verificationStatus\":\"ALREADY_FULL\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(progress.attemptedIds().isEmpty());
        assertEquals(List.of(17L), progress.verifiedIds());
    }

    @Test
    void fiveFailedVerificationsStillExhaustAttemptBudget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        for (int i = 1; i <= 5; i++)
            verify(log, "failed" + i, i, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"error\":{\"code\":\"BOSS_DETAIL_INVALID\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(5, progress.attemptedIds().size());
        assertTrue(progress.verifiedIds().isEmpty());
        assertTrue(JobSearchWorkflow.refuse(progress, "verify_boss_job", mapper.readTree("{\"jobId\":6}")).contains("核实上限"));
    }

    @Test
    void missingJobBeforeBrowserRefundsVerificationAndDailyPageEstimate() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[91,92],\"dailyPagesLeft\":8}");
        verify(log, "missing", 91, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"executed\":false,\"error\":{\"code\":\"BOSS_VERIFY_JOB_NOT_FOUND\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(progress.attemptedIds().isEmpty());
        assertTrue(progress.verifiedIds().isEmpty());
        assertEquals(8, progress.dailyPagesLeft());
    }

    @Test
    void invalidUrlAndUnsupportedSourceBeforeBrowserRefundVerificationAndDailyPageEstimate() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[91,92],\"dailyPagesLeft\":8}");
        verify(log, "invalidUrl", 91, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"executed\":false,\"error\":{\"code\":\"BOSS_DETAIL_URL_INVALID\"}}");
        var afterUrl = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(afterUrl.attemptedIds().isEmpty());
        assertEquals(8, afterUrl.dailyPagesLeft());
        verify(log, "unsupported", 92, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"executed\":false,\"error\":{\"code\":\"BOSS_DETAIL_UNSUPPORTED\"}}");
        var afterSource = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(afterSource.attemptedIds().isEmpty());
        assertTrue(afterSource.verifiedIds().isEmpty());
        assertEquals(8, afterSource.dailyPagesLeft());
    }

    @Test
    void invalidReportAfterBrowserDoesNotRefundVerificationAttempt() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[91],\"dailyPagesLeft\":8}");
        verify(log, "invalidReport", 91, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"error\":{\"code\":\"BOSS_DETAIL_INVALID\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(91L), progress.attemptedIds());
        assertTrue(progress.verifiedIds().isEmpty());
        assertEquals(7, progress.dailyPagesLeft());
    }

    @Test
    void preflightRefundAtZeroDailyPagesDoesNotInventAvailablePages() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[91],\"dailyPagesLeft\":0}");
        verify(log, "missing", 91, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"executed\":false,\"error\":{\"code\":\"BOSS_VERIFY_JOB_NOT_FOUND\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(progress.attemptedIds().isEmpty());
        assertEquals(0, progress.dailyPagesLeft());
        verify(log, "full", 91, "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":91,\"captureDepth\":\"FULL\",\"verificationStatus\":\"ALREADY_FULL\"}}");
        var full = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(full.attemptedIds().isEmpty());
        assertEquals(List.of(91L), full.verifiedIds());
        assertEquals(0, full.dailyPagesLeft());
    }

    @Test
    void verificationIsRefusedWhenTheDailyPagesAreGone() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[1,2],\"dailyPagesLeft\":1}");
        verify(log, "v1", 1, "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":1,\"captureDepth\":\"FULL\",\"verificationStatus\":\"SAVED_FULL\"}}");
        assertTrue(JobSearchWorkflow.refuse(JobSearchWorkflow.inspect(log, mapper), "verify_boss_job",
                mapper.readTree("{\"jobId\":2}")).contains("剩余页数已用完"));
    }

    @Test
    void agentLoopReallyRejectsTheSixthVerificationBeforeTheBrowserIsAsked() throws Exception {
        var properties = new AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        AtomicInteger verifications = new AtomicInteger();
        DeferrableTool verifier = new DeferrableTool() {
            public String name() { return "verify_boss_job"; }
            public String description() { return "test"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("jobId", "job", true, 1, Long.MAX_VALUE); }
            public Begin begin(JsonNode arguments, AgentToolContext context) {
                verifications.incrementAndGet();
                return new Done(Map.of("jobId", arguments.path("jobId").asLong(), "captureDepth", "FULL", "verificationStatus", "SAVED_FULL"));
            }
            public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) { return Map.of(); }
        };
        AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results == 6) return AgentModel.Reply.answer("已核实 5 个，其余待核实");
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("v" + (results + 1), "verify_boss_job",
                    "{\"jobId\":" + (results + 1) + "}"));
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(verifier)), mapper, properties,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertEquals(JobSearchWorkflow.MAX_VERIFICATIONS, verifications.get());
    }

    @Test
    void agentLoopPreflightErrorsNeverStartBrowserOrSpendDailyPages() throws Exception {
        var properties = new AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        DeferrableTool collector = new DeferrableTool() {
            public String name() { return "collect_boss_jobs"; }
            public String description() { return "test"; }
            public ToolSchema parameters() {
                return ToolSchema.object().string("keyword", "keyword", true, 50)
                        .string("city", "city", true, 10)
                        .string("experience", "experience", false, 10)
                        .integer("maxPages", "pages", false, 1, 3);
            }
            public Begin begin(JsonNode arguments, AgentToolContext context) {
                return new Done(Map.of("pagesRead", 1, "jobIds", List.of(404L, 18L), "dailyPagesLeft", 8));
            }
            public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) { return Map.of(); }
        };
        var jobs = org.mockito.Mockito.mock(com.arthur.jdragresume.service.JobDescriptionService.class);
        org.mockito.Mockito.when(jobs.findById(404L)).thenThrow(
                new com.arthur.jdragresume.exception.ResourceNotFoundException("JobDescription", 404L));
        org.mockito.Mockito.when(jobs.findById(18L)).thenReturn(
                new com.arthur.jdragresume.dto.job.JobDescriptionResponse(
                        18L, 1L, "Java 实习", "示例科技", "深圳", "实习", "在校生",
                        "列表摘要", "本科", "BOSS",
                        "https://evil.example/job_detail/abc123.html", "abc123",
                        null, null, "LISTING", null, null));
        var collectors = org.mockito.Mockito.mock(com.arthur.jdragresume.collect.RoutingJobCollector.class);
        var verifier = new com.arthur.jdragresume.agent.tool.VerifyBossJobTool(jobs, collectors);
        AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results == 0) return AgentModel.Reply.call(AgentMessage.ToolCall.of("resume", "get_resume", "{\"resumeId\":1}"));
            if (results == 1) return AgentModel.Reply.call(AgentMessage.ToolCall.of("prefs", "get_job_search_preferences", "{}"));
            if (results == 2) return AgentModel.Reply.call(AgentMessage.ToolCall.of("collect", "collect_boss_jobs",
                    "{\"keyword\":\"Java后端\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}"));
            if (results == 3) return AgentModel.Reply.call(AgentMessage.ToolCall.of("missing", "verify_boss_job",
                    "{\"jobId\":404}"));
            if (results == 4) return AgentModel.Reply.call(AgentMessage.ToolCall.of("bad-url", "verify_boss_job",
                    "{\"jobId\":18}"));
            return AgentModel.Reply.answer("两条职位都无法开始浏览器核实");
        };
        AgentTool resume = new AgentTool() {
            public String name() { return "get_resume"; }
            public String description() { return "test"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("resumeId", "id", true, 1, 10); }
            public Object execute(JsonNode args, AgentToolContext context) { return Map.of("resumeId", 1, "text", "Java"); }
        };
        AgentTool prefs = new AgentTool() {
            public String name() { return "get_job_search_preferences"; }
            public String description() { return "test"; }
            public ToolSchema parameters() { return ToolSchema.object(); }
            public Object execute(JsonNode args, AgentToolContext context) { return Map.of("configured", true); }
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(collector, verifier, resume, prefs)), mapper, properties,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        var toolResults = result.transcript().stream().filter(m -> AgentMessage.TOOL.equals(m.role())).toList();
        assertEquals(5, toolResults.size());
        assertEquals("BOSS_VERIFY_JOB_NOT_FOUND", mapper.readTree(toolResults.get(3).content())
                .path("error").path("code").asText());
        assertEquals("BOSS_DETAIL_URL_INVALID", mapper.readTree(toolResults.get(4).content())
                .path("error").path("code").asText());
        var progress = JobSearchWorkflow.inspect(result.transcript(), mapper);
        assertTrue(progress.attemptedIds().isEmpty());
        assertTrue(progress.verifiedIds().isEmpty());
        assertEquals(8, progress.dailyPagesLeft());
        org.mockito.Mockito.verifyNoInteractions(collectors);
    }

    @Test
    void aStoredFullJdBypassesFiveSpentAttemptsWithoutOpeningTheBrowser() throws Exception {
        var properties = new AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        var jobs = org.mockito.Mockito.mock(com.arthur.jdragresume.service.JobDescriptionService.class);
        org.mockito.Mockito.when(jobs.findById(org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            String sourceId = "post" + id;
            return new com.arthur.jdragresume.dto.job.JobDescriptionResponse(id, 1L, "Java 实习", "示例科技", "深圳",
                    "实习", "在校生", "（BOSS 搜索列表摘要）", "本科", "BOSS",
                    "https://www.zhipin.com/job_detail/" + sourceId + ".html", sourceId,
                    null, null, id == 17 ? "FULL" : "LISTING", null, null);
        });
        var collectors = org.mockito.Mockito.mock(com.arthur.jdragresume.collect.RoutingJobCollector.class);
        org.mockito.Mockito.when(collectors.startAction(org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.nullable(com.arthur.jdragresume.collect.CollectionSession.class)))
                .thenThrow(new com.arthur.jdragresume.exception.BusinessException("BOSS_DETAIL_UNAVAILABLE", "详情页读取失败"));
        var verifier = new com.arthur.jdragresume.agent.tool.VerifyBossJobTool(jobs, collectors);
        AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results == 6) return AgentModel.Reply.answer("完整 JD 已核实");
            long jobId = results == 5 ? 17 : results + 1;
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("v" + (results + 1), "verify_boss_job",
                    "{\"jobId\":" + jobId + "}"));
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(verifier)), mapper, properties,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        var progress = JobSearchWorkflow.inspect(result.transcript(), mapper);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), progress.attemptedIds(),
                "five failed browser attempts still consume the verification budget");
        assertEquals(List.of(17L), progress.verifiedIds(), "stored FULL adds only a verified id");
        var toolResults = result.transcript().stream().filter(m -> AgentMessage.TOOL.equals(m.role())).toList();
        assertEquals(6, toolResults.size());
        assertEquals("ALREADY_FULL", mapper.readTree(toolResults.get(5).content())
                .path("untrusted_data").path("verificationStatus").asText());
        org.mockito.Mockito.verify(jobs, org.mockito.Mockito.times(1)).findById(17L);
        org.mockito.Mockito.verify(collectors, org.mockito.Mockito.times(5)).startAction(
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.nullable(com.arthur.jdragresume.collect.CollectionSession.class));
    }

    @Test
    void everyBossToolInTheRegistryIsGovernedByTheBudget() throws Exception {
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(
                com.arthur.jdragresume.agent.tool.AgentTool.class));
        int bossTools = 0;
        for (var candidate : scanner.findCandidateComponents("com.arthur.jdragresume.agent.tool")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (!type.getSimpleName().contains("Boss")) continue;
            var tool = (com.arthur.jdragresume.agent.tool.AgentTool) org.mockito.Mockito.mock(type,
                    org.mockito.Mockito.CALLS_REAL_METHODS);
            assertTrue(JobSearchWorkflow.governs(tool.name()),
                    type.getSimpleName() + " loads BOSS pages, so JobSearchWorkflow.BOSS_TOOLS must list it");
            bossTools++;
        }
        assertEquals(JobSearchWorkflow.BOSS_TOOLS.size(), bossTools);
    }

    @Test
    void stopReasonsDoNotPermitAdditionalSearches() throws Exception {
        for (String finish : List.of("NO_RESULTS", "MAX_PAGES", "MAX_JOBS", "NO_MORE_RESULTS")) {
            int pages = "NO_MORE_RESULTS".equals(finish) ? 2 : 1;
            assertNull(JobSearchStopPolicy.haltReason(mapper.readTree(
                    "{\"ok\":true,\"untrusted_data\":{\"finishReason\":\"" + finish + "\",\"pagesRead\":" + pages + "}}")));
        }
        assertTrue(JobSearchStopPolicy.haltReason(mapper.readTree(
                "{\"ok\":true,\"untrusted_data\":{\"finishReason\":\"NO_MORE_RESULTS\",\"pagesRead\":1}}"))
                .contains("疑似未登录"));
        for (String stop : List.of("CAPTCHA", "BOSS_NOT_LOGGED_IN", "USER_CANCELLED", "STRUCTURE_CHANGED")) {
            assertTrue(JobSearchStopPolicy.haltReason(mapper.readTree(
                    "{\"ok\":true,\"untrusted_data\":{\"status\":\"STOPPED\",\"stopReason\":\"" + stop + "\"}}"))
                    .contains(stop));
        }
        assertNotNull(JobSearchStopPolicy.haltReason(mapper.readTree(
                "{\"ok\":true,\"untrusted_data\":{\"finishReason\":\"DAILY_LIMIT\"}}")));
    }

    @Test
    void promptAndCollectionDescriptionShareStopRules() {
        String guidance = JobSearchStopPolicy.guidance();
        String prompt = AgentLoop.SYSTEM_PROMPT + "\n" + guidance;
        String tool = new com.arthur.jdragresume.agent.tool.CollectBossJobsTool(null, null, null).description();
        assertTrue(tool.contains(guidance));
        for (String conflict : List.of("提议重新采集", "提议换关键词", "询问是否重新采集", "不自动反复采集")) {
            assertFalse(prompt.contains(conflict), conflict);
            assertFalse(tool.contains(conflict), conflict);
        }
    }

    @Test
    void eligibleCandidatesRequireVerifiedFullJdAndUnknownDoesNotReachTarget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        add(log, "search", "Java后端", 1, "{\"pagesRead\":1,\"jobIds\":[1,2,3,4,5]}");
        verify(log, "v1", 1, saved(1, "SAVED_FULL"));
        verify(log, "v2", 2, saved(2, "ALREADY_FULL"));
        verify(log, "v3", 3, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"error\":{\"code\":\"BOSS_DETAIL_INVALID\"}}");
        verify(log, "v4", 4, saved(4, "SAVED_FULL"));
        check(log, "c1", 1, true, false, true, false);
        check(log, "c2", 2, true, false, true, false);
        check(log, "c3", 3, true, false, true, false); // failed verification must never count
        check(log, "c4", 4, false, false, true, true);
        check(log, "c5", 5, true, false, true, false); // FULL-looking check alone cannot prove verification
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(1L, 3L, 4L), progress.attemptedIds());
        assertEquals(List.of(1L, 2L, 4L), progress.verifiedIds());
        assertEquals(2, progress.qualifiedIds().size());
        assertEquals(1, progress.unknownIds().size());
        assertFalse(progress.reachedTarget());
        verify(log, "v5", 5, saved(5, "ALREADY_FULL"));
        progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(3, progress.qualifiedIds().size());
        assertTrue(progress.reachedTarget());
        assertNotNull(JobSearchWorkflow.refuse(progress, mapper.readTree(
                "{\"keyword\":\"Java开发\",\"city\":\"深圳\",\"maxPages\":1}")));
    }

    @Test
    void threeVerifiedAndEligibleCandidatesCauseEarlyCompletion() {
        var settings = new AgentProperties();
        settings.setMaxSteps(12);
        settings.setMaxPlanningSteps(12);
        AtomicInteger verified = new AtomicInteger();
        AtomicInteger checked = new AtomicInteger();
        DeferrableTool verifier = new DeferrableTool() {
            public String name() { return "verify_boss_job"; }
            public String description() { return "verify"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("jobId", "id", true, 1, 30); }
            public Begin begin(JsonNode args, AgentToolContext context) {
                verified.incrementAndGet();
                return new Done(Map.of("jobId", args.path("jobId").asLong(), "captureDepth", "FULL", "verificationStatus", "SAVED_FULL"));
            }
            public Object resume(JsonNode args, JsonNode report, AgentToolContext context) { return Map.of(); }
        };
        AgentTool checkTool = new AgentTool() {
            public String name() { return "check_job_preferences"; }
            public String description() { return "check"; }
            public ToolSchema parameters() { return ToolSchema.object().integer("jobId", "id", true, 1, 30); }
            public Object execute(JsonNode args, AgentToolContext context) {
                checked.incrementAndGet();
                return Map.of("jobId", args.path("jobId").asLong(), "captureDepth", "FULL",
                        "findings", Map.of("hardBlock", false, "jdConditionsMet", true));
            }
        };
        AgentModel model = (system, transcript, tools, timeout) -> {
            if (tools.isEmpty()) return AgentModel.Reply.answer("已找到符合条件的完整 JD 岗位");
            int results = (int) transcript.stream().filter(m -> AgentMessage.TOOL.equals(m.role())).count();
            if (results % 2 == 0) return AgentModel.Reply.call(AgentMessage.ToolCall.of("v" + results,
                    "verify_boss_job", "{\"jobId\":" + (results / 2 + 1) + "}"));
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("c" + results,
                    "check_job_preferences", "{\"jobId\":" + (results / 2 + 1) + "}"));
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(verifier, checkTool)), mapper, settings,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(settings).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertEquals(3, verified.get());
        assertEquals(3, checked.get());
        assertEquals(3, JobSearchWorkflow.inspect(result.transcript(), mapper).qualifiedIds().size());
    }

    private String saved(long id, String status) {
        return "{\"tool\":\"verify_boss_job\",\"ok\":true,\"untrusted_data\":{\"jobId\":" + id
                + ",\"captureDepth\":\"FULL\",\"verificationStatus\":\"" + status + "\"}}";
    }

    private void check(List<AgentMessage> log, String callId, long jobId, boolean met,
                       boolean blocked, boolean full, boolean unknown) {
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(callId,
                "check_job_preferences", "{\"jobId\":" + jobId + "}"))));
        log.add(AgentMessage.tool(callId, "{\"tool\":\"check_job_preferences\",\"ok\":true,\"untrusted_data\":{"
                + "\"jobId\":" + jobId + ",\"captureDepth\":\"" + (full ? "FULL" : "LISTING")
                + "\",\"findings\":{\"hardBlock\":" + blocked + ",\"hasUnknown\":" + unknown + ",\"jdConditionsMet\":" + met
                + ",\"duration\":{\"status\":\"" + (unknown ? "UNKNOWN" : "MEETS") + "\"}}}}"));
    }

    private void verify(List<AgentMessage> log, String id, long jobId, String result) {
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(id, "verify_boss_job",
                "{\"jobId\":" + jobId + "}"))));
        log.add(AgentMessage.tool(id, result));
    }

    private void add(List<AgentMessage> log, String id, String keyword, int pages, String data) throws Exception {
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(id, "collect_boss_jobs",
                "{\"keyword\":\"" + keyword + "\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":" + pages + "}"))));
        log.add(AgentMessage.tool(id, "{\"tool\":\"collect_boss_jobs\",\"ok\":true,\"untrusted_data\":" + data + "}"));
    }

    @Test
    void educationUnknownNeverCountsAsQualifiedButNotMentionedDoes() {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        verify(log, "v101", 101, saved(101, "SAVED_FULL"));
        verify(log, "v102", 102, saved(102, "SAVED_FULL"));
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c101",
                "check_job_preferences", "{\"jobId\":101}"))));
        log.add(AgentMessage.tool("c101", "{\"tool\":\"check_job_preferences\",\"ok\":true,"
                + "\"untrusted_data\":{\"jobId\":101,\"captureDepth\":\"FULL\","
                + "\"findings\":{\"hardBlock\":false,\"hasUnknown\":true,\"jdConditionsMet\":false,"
                + "\"education\":{\"status\":\"UNKNOWN\"}}}}"));
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c102",
                "check_job_preferences", "{\"jobId\":102}"))));
        log.add(AgentMessage.tool("c102", "{\"tool\":\"check_job_preferences\",\"ok\":true,"
                + "\"untrusted_data\":{\"jobId\":102,\"captureDepth\":\"FULL\","
                + "\"findings\":{\"hardBlock\":false,\"jdConditionsMet\":true,"
                + "\"education\":{\"status\":\"NOT_MENTIONED\"},\"experience\":{\"status\":\"UNRESTRICTED\"}}}}"));
        var result = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(Set.of(102L), result.qualifiedIds());
        assertEquals(Set.of(101L), result.unknownIds());
    }

}
