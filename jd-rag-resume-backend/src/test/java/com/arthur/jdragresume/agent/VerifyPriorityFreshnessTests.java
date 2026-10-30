package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentTool;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.GetResumeTool;
import com.arthur.jdragresume.agent.tool.RankJobsForResumeTool;
import com.arthur.jdragresume.agent.tool.SubmitRecommendationsTool;
import com.arthur.jdragresume.agent.tool.ToolSchema;
import com.arthur.jdragresume.agent.tool.VerifyBossJobTool;
import com.arthur.jdragresume.collect.CollectionSession;
import com.arthur.jdragresume.collect.RoutingJobCollector;
import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.arthur.jdragresume.service.JobSemanticMatchService;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verification slots go only to candidates without a known hard conflict, and a verified JD expires after the one
 * configured lifetime that ranking, the verification budget and the recommendation gate share.
 */
class VerifyPriorityFreshnessTests {
    private static final Duration MAX_AGE = Duration.ofDays(7);
    private static final String EVIDENCE = "负责 Java 后端接口开发";
    private static final String QUOTE = "熟练使用 Spring Boot、MySQL 构建 REST API";

    private final ObjectMapper mapper = new ObjectMapper();
    private final JobDescriptionService jobs = mock(JobDescriptionService.class);
    private final JobSearchPreferencesService preferences = mock(JobSearchPreferencesService.class);
    private final ResumeService resumes = mock(ResumeService.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-30T02:00:00Z"));
    private final JdFreshness freshness = new JdFreshness(clock, MAX_AGE);
    private final VerifyPriority priority = new VerifyPriority(jobs, preferences, freshness);
    private final Map<Long, JobDescriptionResponse> library = new HashMap<>();
    private final BrowserTaskRegistry tasks = new BrowserTaskRegistry();
    private final AgentProperties properties = new AgentProperties();
    private final AgentToolContext context = new AgentToolContext(user(1L));
    private final RecordingCollectors collectors = new RecordingCollectors();

    @BeforeEach
    void setUp() {
        // 大专 (level 1): a listing tagged 本科 is already a hard conflict.
        when(preferences.forUser(any())).thenReturn(new JobSearchPreferencesView(true, 6, 5, null, null, false,
                1, null, 0, true));
        when(jobs.findById(anyLong())).thenAnswer(call -> {
            JobDescriptionResponse job = library.get(call.<Long>getArgument(0));
            if (job == null) throw new com.arthur.jdragresume.exception.ResourceNotFoundException("JobDescription", call.getArgument(0));
            return job;
        });
        when(jobs.capture(any())).thenAnswer(call -> {
            JobCaptureRequest request = call.getArgument(0);
            JobDescriptionResponse old = library.values().stream()
                    .filter(job -> job.sourceJobId().equals(request.sourceJobId())).findFirst().orElseThrow();
            JobDescriptionResponse full = job(old.id(), old.title(), old.location(), request.description(),
                    request.requirements(), "FULL", LocalDateTime.now(clock));
            library.put(full.id(), full);
            return new JobCaptureResponse(full, true, true);
        });
        when(resumes.findById(1L)).thenReturn(new ResumeResponse(1L, 1L, "owner", "Java 简历", null, null, null,
                null, null, null, null, QUOTE + "，负责服务端接口开发。", null, null));
    }

    private static AppUser user(long id) {
        AppUser user = new AppUser();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static JobDescriptionResponse job(long id, String title, String location, String description,
                                              String requirements, String depth, LocalDateTime verifiedAt) {
        String sourceId = "post" + id;
        return new JobDescriptionResponse(id, 1L, title, "示例科技", location, "实习", "在校生", description,
                requirements, "BOSS", "https://www.zhipin.com/job_detail/" + sourceId + ".html", sourceId,
                null, null, depth, verifiedAt, null, null);
    }

    private void listing(long id, String tags) {
        library.put(id, job(id, "Java 实习 " + id, "杭州·余杭区·未来科技城", "（BOSS 搜索列表摘要，未读取完整职位描述）",
                tags, "LISTING", null));
    }

    // ---- 一、冲突岗位不占核实名额 ----

    @Test
    void aBlockedCandidateIsRefusedBeforeExecutionAndPointsAtOneThatReallyRuns() throws Exception {
        listing(1, "在校/应届、本科、Java");
        listing(2, "在校/应届、硕士、Java");
        listing(3, "在校/应届、Java");
        AtomicReference<String> refusal = new AtomicReference<>();
        AtomicReference<JobSearchWorkflow.Snapshot> before = new AtomicReference<>();
        AtomicReference<JobSearchWorkflow.Snapshot> after = new AtomicReference<>();
        AtomicReference<Integer> stepsBefore = new AtomicReference<>();
        AtomicReference<Integer> stepsAfter = new AtomicReference<>();
        AgentModel model = (system, transcript, tools, timeout) -> {
            List<JsonNode> results = results(transcript);
            if (results.isEmpty()) return call("collect", "collect_boss_jobs", "{}");
            if (results.size() == 1) return call("rank", "rank_jobs_for_resume", "{}");
            if (results.size() == 2) {
                before.set(JobSearchWorkflow.inspect(transcript, mapper));
                stepsBefore.set(TurnUsage.of(transcript, mapper).toolSteps());
                return call("v1", "verify_boss_job", "{\"jobId\":1}");
            }
            JsonNode refused = results.get(2);
            refusal.set(refused.path("error").path("message").asText());
            after.set(JobSearchWorkflow.inspect(transcript, mapper));
            stepsAfter.set(TurnUsage.of(transcript, mapper).toolSteps());
            return call("v2", "verify_boss_job", "{\"jobId\":" + pointedJob(refusal.get()) + "}");
        };
        List<AgentLoop.BrowserTaskEvent> browser = new ArrayList<>();
        var outcome = run(model, List.of(
                fake("collect_boss_jobs", Map.of("pagesRead", 1, "jobIds", List.of(1, 2, 3), "dailyPagesLeft", 9)),
                fake("rank_jobs_for_resume", Map.of("matches", List.of(Map.of("jobId", 1), Map.of("jobId", 2),
                        Map.of("jobId", 3))))), browser);

        JsonNode refused = results(outcome.transcript()).get(2);
        assertEquals(VerifyPriority.CONFLICT, refused.path("error").path("code").asText());
        assertFalse(ToolEnvelope.executed(refused), "refused before execution");
        assertTrue(refusal.get().contains("学历不符合"), refusal.get());
        assertEquals(before.get().attemptedIds(), after.get().attemptedIds(), "verification budget unchanged");
        assertTrue(after.get().attemptedIds().isEmpty());
        assertEquals(9, after.get().dailyPagesLeft(), "daily page estimate unchanged");
        assertEquals(stepsBefore.get(), stepsAfter.get(), "the refusal takes no step");
        // Job 2 conflicts too (硕士); job 3 has only UNKNOWN conditions and is the one named.
        assertEquals(3L, pointedJob(refusal.get()));
        assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state(), "the named job really starts verification");
        assertEquals(1, browser.size());
        assertEquals(3L, ((Number) browser.getFirst().command().get("jobId")).longValue());
        assertEquals(List.of(3L), collectors.started);
    }

    @Test
    void withNoVerifiableCandidateLeftTheRefusalNamesNoJob() throws Exception {
        listing(1, "在校/应届、本科、Java");
        listing(2, "在校/应届、硕士");
        AgentModel model = (system, transcript, tools, timeout) -> switch (results(transcript).size()) {
            case 0 -> call("rank", "rank_jobs_for_resume", "{}");
            case 1 -> call("v1", "verify_boss_job", "{\"jobId\":1}");
            default -> AgentModel.Reply.answer("说明冲突并收尾");
        };
        var outcome = run(model, List.of(fake("rank_jobs_for_resume",
                Map.of("matches", List.of(Map.of("jobId", 1), Map.of("jobId", 2)))) ), new ArrayList<>());
        String message = results(outcome.transcript()).get(1).path("error").path("message").asText();
        assertFalse(message.contains("jobId="), message);
        assertFalse(message.contains("verify_boss_job("), message);
        assertTrue(message.contains("收尾") && message.contains("放宽"), message);
        assertTrue(collectors.started.isEmpty());
    }

    @Test
    void anOtherwiseFineCandidateIsNotNamedWhenTheBudgetWouldRefuseIt() {
        listing(1, "在校/应届、本科");
        listing(3, "在校/应届、Java");
        String message = priority.refusal(1L, null, JobSearchContext.empty(), List.of(1L, 3L),
                id -> "本次找岗位已尝试核实 5 次，达到核实上限");
        assertNotNull(message);
        assertFalse(message.contains("jobId="), message);
    }

    @Test
    void anUnknownCandidateIsVerifiedAsUsual() throws Exception {
        listing(3, "在校/应届、Java");
        List<AgentLoop.BrowserTaskEvent> browser = new ArrayList<>();
        var outcome = run((system, transcript, tools, timeout) -> call("v", "verify_boss_job", "{\"jobId\":3}"),
                List.of(), browser);
        assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state());
        assertEquals(List.of(3L), collectors.started);
        var findings = com.arthur.jdragresume.service.JobAvailabilityMatcher.compare(preferences.forUser(null),
                "LISTING", library.get(3L).description(), library.get(3L).requirements(), library.get(3L).location());
        assertTrue(findings.hasUnknown(), "UNKNOWN is what verification resolves, not a conflict");
        assertFalse(findings.hardBlock());
    }

    @Test
    void hardConflictsComeOnlyFromDefiniteData() {
        var own = preferences.forUser(null);
        JobSearchContext hangzhou = JobSearchContext.empty().patch(mapper.createObjectNode().put("city", "杭州"));
        assertNull(VerifyPriority.hardConflict(library(1, "杭州·余杭区·未来科技城", "在校/应届、大专"), own, hangzhou));
        assertNotNull(VerifyPriority.hardConflict(library(1, "上海·浦东新区·张江", "在校/应届"), own, hangzhou));
        assertNotNull(VerifyPriority.hardConflict(library(1, "深圳市龙岗区", "在校/应届"), own, hangzhou), "older plain text");
        assertNull(VerifyPriority.hardConflict(library(1, "余杭区", "在校/应届"), own, hangzhou), "no city named");
        assertNull(VerifyPriority.hardConflict(library(1, "上海·浦东新区", "在校/应届"), own, JobSearchContext.empty()));
        // A student with no work experience: a 社招 tag is a conflict, 经验不限 and 1年以内 are not.
        assertNotNull(VerifyPriority.hardConflict(library(1, "杭州·西湖区", "3-5年、大专"), own, hangzhou));
        assertNotNull(VerifyPriority.hardConflict(library(1, "杭州·西湖区", "1-3年、大专"), own, hangzhou));
        assertNull(VerifyPriority.hardConflict(library(1, "杭州·西湖区", "1年以内、大专"), own, hangzhou));
        assertNull(VerifyPriority.hardConflict(library(1, "杭州·西湖区", "经验不限、学历不限"), own, hangzhou));
        // Without stated work years only a clear 社招 requirement (3 years and up) rules a student out.
        var noYears = new JobSearchPreferencesView(true, 6, 5, null, null, false, 2, null, null, true);
        assertNull(VerifyPriority.hardConflict(library(1, "杭州·西湖区", "1-3年、本科"), noYears, hangzhou));
        assertNotNull(VerifyPriority.hardConflict(library(1, "杭州·西湖区", "5-10年、本科"), noYears, hangzhou));
    }

    private static JobDescriptionResponse library(long id, String location, String tags) {
        return job(id, "岗位", location, "（BOSS 搜索列表摘要，未读取完整职位描述）", tags, "LISTING", null);
    }

    @Test
    void rankingPutsEveryBlockedCandidateAfterTheOthersAndNeverSuggestsVerifyingIt() throws Exception {
        listing(1, "在校/应届、本科");
        listing(2, "在校/应届、Java");
        listing(3, "在校/应届、硕士");
        listing(4, "在校/应届、Python");
        JobSemanticMatchService semantic = mock(JobSemanticMatchService.class);
        when(semantic.rank(eq(1L), anyInt(), any())).thenReturn(List.of(
                new JobSemanticMatchResponse(library.get(1L), 0.9), new JobSemanticMatchResponse(library.get(2L), 0.8),
                new JobSemanticMatchResponse(library.get(3L), 0.7), new JobSemanticMatchResponse(library.get(4L), 0.6)));
        var rank = new RankJobsForResumeTool(semantic, preferences, freshness, null);

        JsonNode all = mapper.valueToTree(rank.execute(mapper.readTree("{\"resumeId\":1,\"limit\":4}"), context));
        assertEquals(List.of(2L, 4L, 1L, 3L), ids(all.path("matches")));
        assertTrue(all.path("matches").get(2).hasNonNull("hardConflict"));
        assertFalse(all.path("matches").get(0).has("hardConflict"));
        assertEquals(List.of(2L, 4L), ids(all.path("suggestedVerify")));

        JsonNode top = mapper.valueToTree(rank.execute(mapper.readTree("{\"resumeId\":1,\"limit\":2}"), context));
        assertEquals(List.of(2L, 4L), ids(top.path("matches")), "a conflict never displaces a job that fits");
    }

    // ---- 二、旧 JD 过期重新核实 ----

    @Test
    void freshnessHoldsUpToTheThresholdAndExpiresRightAfterIt() {
        JobDescriptionResponse verified = job(7, "Java 后端实习", "杭州·余杭区", EVIDENCE, "", "FULL", LocalDateTime.now(clock));
        assertEquals(JdFreshness.Status.FRESH, freshness.of(verified));
        clock.advance(MAX_AGE);
        assertEquals(JdFreshness.Status.FRESH, freshness.of(verified), "exactly at the threshold is still fresh");
        clock.advance(Duration.ofSeconds(1));
        assertEquals(JdFreshness.Status.EXPIRED, freshness.of(verified));
        assertTrue(freshness.needsVerification(verified));
        assertEquals(JdFreshness.Status.EXPIRED, freshness.of(job(8, "旧数据", "杭州", EVIDENCE, "", "FULL", null)),
                "a FULL row from before the column counts as expired");
        JobDescriptionResponse typed = new JobDescriptionResponse(9L, 1L, "手动录入", "公司", "杭州", "全职", null,
                EVIDENCE, "", null, null, null, null, null, "FULL", null, null, null);
        assertEquals(JdFreshness.Status.NOT_APPLICABLE, freshness.of(typed), "nothing to re-read for a pasted JD");
    }

    /** Ranking, the budget and the gate must ask JdFreshness; a second age check could drift from the first. */
    @Test
    void onlyJdFreshnessJudgesTheAgeOfAVerification() throws Exception {
        Path sources = Path.of("src/main/java");
        List<String> readers = new ArrayList<>();
        try (var files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains("fullVerifiedAt()") || source.contains("getFullVerifiedAt()")) {
                    readers.add(file.getFileName().toString());
                }
            }
        }
        // The entity and DTO carry the column and the verify tool reports the time; none compares it with a clock.
        assertEquals(List.of("JdFreshness.java", "JobDescription.java", "JobDescriptionResponse.java",
                        "VerifyBossJobTool.java"),
                readers.stream().sorted().toList());
    }

    @Test
    void theGateAcceptsInsideTheThresholdRejectsJustPastItAndAcceptsAfterReVerification() throws Exception {
        library.put(7L, verifiedJob(LocalDateTime.now(clock)));
        var submit = new SubmitRecommendationsTool(jobs, preferences, resumes, freshness, null);
        clock.advance(MAX_AGE);
        assertTrue(submitted(submit).path("accepted").asBoolean(), "inside the threshold");

        clock.advance(Duration.ofSeconds(1));
        JsonNode stale = submitted(submit);
        assertFalse(stale.path("accepted").asBoolean());
        String reason = stale.path("rejected").get(0).path("reason").asText();
        assertTrue(reason.contains("JD 核实已过期"), reason);
        assertTrue(reason.contains("verify_boss_job({\"jobId\":7})"), reason);

        // The budget entry treats the stale JD like a listing: it is charged and opened again, not ALREADY_FULL.
        var verifier = new VerifyBossJobTool(jobs, collectors, freshness);
        assertNull(verifier.beforeBudget(mapper.readTree("{\"jobId\":7}")));
        verifier.resume(mapper.readTree("{\"jobId\":7}"), detailReport(library.get(7L)), context);
        assertTrue(submitted(submit).path("accepted").asBoolean(), "re-verified now");
        assertNotNull(verifier.beforeBudget(mapper.readTree("{\"jobId\":7}")), "fresh again: no second browser trip");
    }

    @Test
    void anExpiredJobThatAlreadyConflictsIsNotSentBackToVerification() throws Exception {
        library.put(7L, job(7, "Java 后端实习", "杭州·余杭区", EVIDENCE + "。学历要求硕士及以上", "", "FULL",
                LocalDateTime.now(clock).minus(MAX_AGE).minusDays(1)));
        JsonNode result = submitted(new SubmitRecommendationsTool(jobs, preferences, resumes, freshness, null));
        String reason = result.path("rejected").get(0).path("reason").asText();
        assertTrue(reason.contains("冲突"), reason);
        assertFalse(reason.contains("verify_boss_job"), "verify would refuse it, so it is not the next step");
    }

    @Test
    void fromAnEmptyConversationAnExpiredJdIsReVerifiedAndResubmittedWithinTheStepBudget() throws Exception {
        library.put(7L, verifiedJob(LocalDateTime.now(clock).minus(MAX_AGE).minusSeconds(1)));
        String submission = "{\"resumeId\":1,\"recommendations\":[{\"jobId\":7,\"reason\":\"技术栈相符\",\"evidence\":\""
                + EVIDENCE + "\",\"resumeEvidence\":[{\"quote\":\"" + QUOTE + "\",\"supports\":\"Java 后端\"}]}]}";
        AgentModel model = (system, transcript, tools, timeout) -> {
            List<JsonNode> results = results(transcript);
            if (results.isEmpty()) return call("r", "get_resume", "{\"resumeId\":1}");
            JsonNode last = results.getLast();
            if (results.size() == 1) return call("s1", "submit_recommendations", submission);
            if ("submit_recommendations".equals(last.path("tool").asText())) {
                String reason = last.path("untrusted_data").path("rejected").get(0).path("reason").asText();
                return call("v", "verify_boss_job", "{\"jobId\":" + pointedJob(reason) + "}");
            }
            return call("s2", "submit_recommendations", submission);
        };
        List<AgentLoop.BrowserTaskEvent> browser = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        var tools = List.<AgentTool>of(new GetResumeTool(resumes),
                new SubmitRecommendationsTool(jobs, preferences, resumes, freshness, null));
        var outcome = run(model, tools, browser, messages);
        assertEquals(AgentLoop.State.AWAITING_BROWSER, outcome.state());
        assertTrue(results(outcome.transcript()).get(1).path("untrusted_data").path("rejected").get(0)
                .path("reason").asText().contains("JD 核实已过期"));

        var task = browser.getFirst();
        var report = new TranscriptPolicy.BrowserReport(task.toolCallId(), task.taskId(), "completed",
                detailReport(library.get(7L)));
        outcome = loop(model, tools).run(new TranscriptPolicy(properties).normalize(outcome.transcript(),
                        task.toolCallId(), call -> "unused"), null, context, sink(browser, messages), () -> false,
                System.currentTimeMillis(), new AgentLoop.TurnOptions(null, List.of(), false, report));

        assertEquals(AgentLoop.State.COMPLETED, outcome.state());
        assertTrue(messages.getLast().startsWith("通过验收的岗位"), messages.getLast());
        assertTrue(TurnUsage.of(outcome.transcript(), mapper).toolSteps() < properties.getMaxSteps());
        assertTrue(results(outcome.transcript()).stream()
                .noneMatch(result -> "AGENT_STEP_LIMIT".equals(result.path("error").path("code").asText())));
        var progress = JobSearchWorkflow.inspect(outcome.transcript(), mapper);
        assertEquals(List.of(7L), progress.attemptedIds(), "re-verifying a stale JD takes a normal slot");
        assertEquals(List.of(7L), progress.verifiedIds());
    }

    private JobDescriptionResponse verifiedJob(LocalDateTime verifiedAt) {
        return job(7, "Java 后端实习", "杭州·余杭区", EVIDENCE + "，参与服务端性能优化。实习至少 3 个月，每周 5 天到岗。", "",
                "FULL", verifiedAt);
    }

    private JsonNode submitted(SubmitRecommendationsTool submit) throws Exception {
        return mapper.valueToTree(submit.execute(mapper.readTree("{\"resumeId\":1,\"recommendations\":[{\"jobId\":7,"
                + "\"reason\":\"技术栈相符\",\"evidence\":\"" + EVIDENCE + "\",\"resumeEvidence\":[{\"quote\":\""
                + QUOTE + "\",\"supports\":\"Java 后端\"}]}]}"), context));
    }

    /** The extension's final event for one detail page; the description is long enough to count as a full JD. */
    private ObjectNode detailReport(JobDescriptionResponse job) {
        ObjectNode report = mapper.createObjectNode().put("type", "result");
        ObjectNode task = report.putObject("task");
        task.putObject("command").put("mode", "detail").put("jobId", job.id())
                .put("sourceJobId", job.sourceJobId()).put("sourceUrl", job.sourceUrl());
        task.put("status", "completed").put("finishReason", "DETAIL_READ");
        task.putArray("jobs").addObject().put("sourcePlatform", "BOSS").put("sourceJobId", job.sourceJobId())
                .put("sourceUrl", job.sourceUrl()).put("title", job.title()).put("companyName", job.companyName())
                .put("location", job.location())
                .put("description", EVIDENCE + "，参与服务端性能优化与接口设计评审。实习至少 3 个月，每周 5 天到岗，"
                        + "熟悉 Spring Boot 与 MySQL，有 REST API 项目经验者优先。");
        return report;
    }

    private AgentLoop.Outcome run(AgentModel model, List<AgentTool> extra, List<AgentLoop.BrowserTaskEvent> browser) {
        return run(model, extra, browser, new ArrayList<>());
    }

    private AgentLoop.Outcome run(AgentModel model, List<AgentTool> extra, List<AgentLoop.BrowserTaskEvent> browser,
                                  List<String> messages) {
        var input = new TranscriptPolicy(properties).normalize(List.of(AgentMessage.user("帮我找杭州 Java 实习")), null);
        return loop(model, extra).run(input, null, context, sink(browser, messages), () -> false);
    }

    private AgentEventSink sink(List<AgentLoop.BrowserTaskEvent> browser, List<String> messages) {
        return (event, value) -> {
            if ("browser_task_required".equals(event)) browser.add((AgentLoop.BrowserTaskEvent) value);
            if ("message".equals(event)) messages.add(((Map<?, ?>) value).get("content").toString());
        };
    }

    private AgentLoop loop(AgentModel model, List<AgentTool> extra) {
        List<AgentTool> tools = new ArrayList<>(extra);
        tools.add(new VerifyBossJobTool(jobs, collectors, freshness));
        return new AgentLoop(model, new AgentToolRegistry(tools), mapper, properties, System::currentTimeMillis,
                tasks, null, JobSearchProfile.UNKNOWN, priority);
    }

    private static AgentModel.Reply call(String id, String name, String arguments) {
        return AgentModel.Reply.call(AgentMessage.ToolCall.of(id, name, arguments));
    }

    private List<JsonNode> results(List<AgentMessage> transcript) {
        List<JsonNode> results = new ArrayList<>();
        for (AgentMessage message : transcript) {
            if (!AgentMessage.TOOL.equals(message.role())) continue;
            try {
                results.add(mapper.readTree(message.content()));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
        return results;
    }

    /** The job id a refusal tells the model to verify next; fails when it names none. */
    private static long pointedJob(String message) {
        Matcher matcher = Pattern.compile("verify_boss_job\\(\\{\"jobId\":(\\d+)}\\)").matcher(message);
        assertTrue(matcher.find(), "no next step in: " + message);
        return Long.parseLong(matcher.group(1));
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : items) ids.add(item.isNumber() ? item.asLong() : item.path("jobId").asLong());
        return ids;
    }

    private static AgentTool fake(String name, Object result) {
        return new AgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "test"; }
            @Override public ToolSchema parameters() { return ToolSchema.object(); }
            @Override public Object execute(JsonNode args, AgentToolContext context) { return result; }
        };
    }

    /** Hands every verification to the browser and records which jobs were opened. */
    private static final class RecordingCollectors extends RoutingJobCollector {
        final List<Long> started = new ArrayList<>();

        RecordingCollectors() {
            super(null, null, null);
        }

        @Override public Action startAction(Map<String, Object> command, CollectionSession session) {
            started.add(((Number) command.get("jobId")).longValue());
            return new Action(command, null, null, 9);
        }

        @Override public Action completeAction(Map<String, Object> command, JsonNode report, CollectionSession session) {
            return new Action(command, report, null, 8);
        }
    }
}
