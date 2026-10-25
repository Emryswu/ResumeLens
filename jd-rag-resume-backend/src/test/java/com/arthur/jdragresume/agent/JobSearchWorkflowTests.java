package com.arthur.jdragresume.agent;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JobSearchWorkflowTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void guidanceOnlyRequestsStudentFilterForExplicitInternshipIntent() {
        for (String question : List.of("找深圳 Java 实习", "找在校兼职", "暑期找岗位")) {
            assertTrue(JobSearchWorkflow.guidance(JobSearchWorkflow.inspect(List.of(AgentMessage.user(question)), mapper))
                    .contains("experience=在校生"), question);
        }
        for (String question : List.of("找深圳 Java 后端全职", "帮我找岗位", "找深圳 Java 社招")) {
            String guidance = JobSearchWorkflow.guidance(JobSearchWorkflow.inspect(List.of(AgentMessage.user(question)), mapper));
            assertFalse(guidance.contains("在校生"), question);
            assertTrue(guidance.contains("experience=不限"), question);
            assertTrue(guidance.contains("没限制经验"), question);
        }
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
            if (results == 4) return AgentModel.Reply.answer("已达到预算，按已有候选说明不足");
            String keyword = switch ((int) results) {
                case 0 -> "Java后端";
                case 1 -> "Java开发";
                case 2 -> "AI应用";
                default -> "Spring Boot";
            };
            return AgentModel.Reply.call(AgentMessage.ToolCall.of("c" + (results + 1), "collect_boss_jobs",
                    "{\"keyword\":\"" + keyword + "\",\"city\":\"深圳\",\"experience\":\"在校生\",\"maxPages\":1}"));
        };
        var loop = new AgentLoop(model, new AgentToolRegistry(List.of(collector)), mapper, properties,
                System::currentTimeMillis);
        var result = loop.run(new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("找深圳实习")), null),
                null, new AgentToolContext(new AppUser()), (name, data) -> {}, () -> false);
        assertEquals(AgentLoop.State.COMPLETED, result.state());
        assertEquals(3, collections.get());
        var failure = mapper.valueToTree(result.transcript().stream()
                .filter(m -> AgentMessage.TOOL.equals(m.role())).toList()).path(3).path("content").asText();
        assertEquals("JOB_SEARCH_BUDGET", mapper.readTree(failure).path("error").path("code").asText());
    }

    @Test
    void verificationsShareTheBudgetAndRefusedCallsDoNotUseItUp() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳 Java 实习")));
        add(log, "a", "Java 后端", 1, "{\"pagesRead\":1,\"jobIds\":[1,2,3,4,5,6,7],\"dailyPagesLeft\":10}");
        for (int i = 1; i <= 4; i++) verify(log, "v" + i, i, "{\"tool\":\"verify_boss_job\",\"ok\":true}");
        verify(log, "dup", 1, "{\"tool\":\"verify_boss_job\",\"ok\":false,\"error\":{\"code\":\"JOB_SEARCH_BUDGET\"}}");
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
        verify(log, "failed", 1, "{\\"tool\\":\\"verify_boss_job\\",\\"ok\\":false,\\"error\\":{\\"code\\":\\"BOSS_DETAIL_UNAVAILABLE\\"}}");
        log.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("pending", "verify_boss_job", "{\\"jobId\\":2}"))));
        var pending = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(1L, 2L), pending.attemptedIds());
        assertTrue(pending.verifiedIds().isEmpty());
        assertTrue(JobSearchWorkflow.guidance(pending).contains("已核实完整 JD 0 个"));
        log.add(AgentMessage.tool("pending", "{\\"tool\\":\\"verify_boss_job\\",\\"ok\\":true,\\"untrusted_data\\":{\\"jobId\\":2,\\"captureDepth\\":\\"FULL\\",\\"verificationStatus\\":\\"SAVED_FULL\\"}}"));
        var completed = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(List.of(1L, 2L), completed.attemptedIds());
        assertEquals(List.of(2L), completed.verifiedIds());
    }

    @Test
    void alreadyFullIsVerifiedWithoutSpendingAttemptBudget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        verify(log, "full", 17, "{\\"tool\\":\\"verify_boss_job\\",\\"ok\\":true,\\"untrusted_data\\":{\\"jobId\\":17,\\"captureDepth\\":\\"FULL\\",\\"verificationStatus\\":\\"ALREADY_FULL\\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertTrue(progress.attemptedIds().isEmpty());
        assertEquals(List.of(17L), progress.verifiedIds());
    }

    @Test
    void fiveFailedVerificationsStillExhaustAttemptBudget() throws Exception {
        List<AgentMessage> log = new ArrayList<>(List.of(AgentMessage.user("找深圳实习")));
        for (int i = 1; i <= 5; i++)
            verify(log, "failed" + i, i, "{\\"tool\\":\\"verify_boss_job\\",\\"ok\\":false,\\"error\\":{\\"code\\":\\"BOSS_DETAIL_INVALID\\"}}");
        var progress = JobSearchWorkflow.inspect(log, mapper);
        assertEquals(5, progress.attemptedIds().size());
        assertTrue(progress.verifiedIds().isEmpty());
        assertTrue(JobSearchWorkflow.refuse(progress, "verify_boss_job", mapper.readTree("{\\"jobId\\":6}")).contains("核实上限"));
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
}
