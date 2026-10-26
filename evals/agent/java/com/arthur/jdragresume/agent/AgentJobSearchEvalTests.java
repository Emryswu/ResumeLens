package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.*;
import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.ai.AiProperties;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test-only harness. All fixtures and evaluation policy live under evals/agent.
 * No production services, browser, database or BOSS network connection is started.
 */
class AgentJobSearchEvalTests {
    private static final Path ROOT = Path.of("..", "evals", "agent");
    private static final String BASELINE = "5fdaca01bf2c38a625b6bf98616a0c11bff6dde4";
    /** #109 changed the agent after reading v1 acceptance output, so v1 takes no further formal runs (see RUN-LOG). */
    private static final boolean V1_CLOSED = true;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> CHECKS = Set.of("prerequisites", "inherit_internship",
            "stop_first_page", "stop_login", "no_listing_as_full", "submit_accepted",
            "replan_offtarget", "fallback_trace", "education_master_excluded",
            "education_master_not_accepted", "education_preferred_accepted");

    private static JsonNode read(String relative) throws Exception {
        try (var in = Files.newInputStream(ROOT.resolve(relative))) {
            return JSON.readTree(in);
        }
    }

    private static List<JsonNode> tasks(String group) throws Exception {
        var list = new ArrayList<JsonNode>();
        for (JsonNode item : read("tasks/" + group + ".json")) {
            assertFalse(item.path("turns").isEmpty(), item.toString());
            for (JsonNode check : item.path("checks")) {
                assertTrue(CHECKS.contains(check.asText()), item.path("id") + ": " + check);
            }
            list.add(item);
        }
        return list;
    }

    @Test
    void offlineScriptedModelChecksTheEvaluationPipeline() throws Exception {
        assertTrue(Files.exists(ROOT.resolve("PROTOCOL.md")));
        assertTrue(Files.exists(ROOT.resolve("snapshots/listings/first-page-exhausted.json")));
        assertTrue(Files.exists(ROOT.resolve("snapshots/listings/not-logged-in.json")));
        assertTrue(Files.exists(ROOT.resolve("snapshots/details/job-101.json")));
        assertTrue(Files.exists(ROOT.resolve("snapshots/details/job-104.json")));
        assertTrue(Files.exists(ROOT.resolve("snapshots/details/job-105.json")));
        for (String group : List.of("dev", "acceptance/v1")) {
            List<JsonNode> scenarios = tasks(group);
            assertFalse(scenarios.isEmpty());
            for (JsonNode task : scenarios) {
                int passed = 0;
                for (int trial = 0; trial < 3; trial++) {
                    Trial result = run(task, new ScriptedAgentModel(JSON));
                    assertEquals(task.path("checks").size(), result.checks().size());
                    assertFalse(result.transcript().isEmpty());
                    assertNotNull(result.category());
                    assertTrue(result.trace().stream().noneMatch(line -> line.startsWith("evaluation exception:")),
                            "评测框架抛出异常: " + task.path("id").asText() + " " + result.trace());
                    if (task.path("id").asText().contains("stop")) {
                        assertTrue(result.trace().contains("browser_task_required"),
                                "停止任务必须真正执行一次快照浏览器回放");
                    }
                    if (result.passed()) passed++;
                }
                System.out.println("Agent scripted eval " + task.path("id").asText()
                        + ": " + passed + "/3 (" + (passed * 100 / 3) + "%)");
            }
        }
    }

    @Test
    void graderRejectsFabricatedRecommendationsAndRecognizesStepLimits() throws Exception {
        JsonNode task = tasks("acceptance/v1").stream()
                .filter(item -> "a4-submit-verified".equals(item.path("id").asText()))
                .findFirst().orElseThrow();
        List<AgentMessage> fabricated = List.of(
                AgentMessage.user("找深圳实习"),
                AgentMessage.assistant("正式推荐 101 号职位", null));
        List<Check> checks = grade(task, fabricated, List.of(), List.of());
        assertFalse(checks.stream().filter(c -> c.name().equals("submit_accepted")).findFirst().orElseThrow().passed());
        assertEquals("RULE_VIOLATION", category(checks, fabricated, List.of(), AgentLoop.State.COMPLETED, null));
        assertEquals("STEP_LIMIT", category(checks, fabricated, List.of(), AgentLoop.State.STEP_LIMIT, null));
        assertEquals("SNAPSHOT_MISSING", category(checks, fabricated, List.of(), AgentLoop.State.COMPLETED,
                "EVAL_SNAPSHOT_MISSING"));
    }

    @Test
    void inheritedFiltersAreJudgedAtCollectorAndRepeatRefusalsDoNotFailTheTurn() throws Exception {
        JsonNode task = tasks("acceptance/v1").stream()
                .filter(t -> "a1-context-inheritance".equals(t.path("id").asText()))
                .findFirst().orElseThrow();
        List<AgentMessage> messages = List.of(
                AgentMessage.user("找深圳 Java 实习"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                        "initial", "collect_boss_jobs", "{\"city\":\"深圳\",\"experience\":\"在校生\",\"keyword\":\"Java\"}"))),
                AgentMessage.tool("initial", "{\"ok\":true,\"untrusted_data\":{\"jobIds\":[101]}}"),
                AgentMessage.assistant("找到一批", null),
                AgentMessage.user("再找几个"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                        "duplicate", "collect_boss_jobs", "{\"keyword\":\"Java\"}"))),
                AgentMessage.tool("duplicate", "{\"ok\":false,\"error\":{\"code\":\"JOB_SEARCH_REPEAT\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                        "retry", "collect_boss_jobs", "{\"keyword\":\"Spring Boot\"}"))),
                AgentMessage.tool("retry", "{\"ok\":true,\"untrusted_data\":{\"jobIds\":[102]}}"));
        var correct = List.of(new ReplayCommand("initial", "深圳", "在校生", "Java"),
                new ReplayCommand("retry", "深圳", "在校生", "Spring Boot"));
        assertTrue(checkNamed(grade(task, messages, List.of(), correct), "inherit_internship"));
        var wrong = List.of(new ReplayCommand("initial", "深圳", "在校生", "Java"),
                new ReplayCommand("retry", "广州", "不限", "Spring Boot"));
        assertFalse(checkNamed(grade(task, messages, List.of(), wrong), "inherit_internship"));
        // A raw retry with city=深圳 is not enough if the collector received other effective filters.
        assertFalse(checkNamed(grade(task, messages, List.of(),
                List.of(new ReplayCommand("initial", "深圳", "在校生", "Java"))), "inherit_internship"));
    }

    @Test
    void degreeGateGraderUsesAcceptedSubmissionNotModelClaims() throws Exception {
        JsonNode masterTask = tasks("acceptance/v1").stream()
                .filter(t -> "a7-education-master-required".equals(t.path("id").asText()))
                .findFirst().orElseThrow();
        List<AgentMessage> base = List.of(
                AgentMessage.user("核实硕士及以上的岗位"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                        "verify", "verify_boss_job", "{\"jobId\":104}"))),
                AgentMessage.tool("verify", "{\"ok\":true,\"untrusted_data\":{\"jobId\":104,\"captureDepth\":\"FULL\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                        "check", "check_job_preferences", "{\"jobId\":104}"))),
                AgentMessage.tool("check", "{\"ok\":true,\"untrusted_data\":{\"jobId\":104,\"captureDepth\":\"FULL\"}}"));
        var rejected = new ArrayList<>(base);
        rejected.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                "submit", "submit_recommendations", "{\"recommendations\":[{\"jobId\":104}]}"))));
        rejected.add(AgentMessage.tool("submit", "{\"ok\":true,\"untrusted_data\":{\"recommendations\":[],\"rejected\":[{\"jobId\":104}]}}"));
        assertTrue(checkNamed(grade(masterTask, rejected, List.of(), List.of()), "education_master_excluded"));
        var wronglyAccepted = new ArrayList<>(base);
        wronglyAccepted.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of(
                "submit", "submit_recommendations", "{\"recommendations\":[{\"jobId\":104}]}"))));
        wronglyAccepted.add(AgentMessage.tool("submit", "{\"ok\":true,\"untrusted_data\":{\"recommendations\":[{\"jobId\":104}],\"rejected\":[]}}"));
        assertFalse(checkNamed(grade(masterTask, wronglyAccepted, List.of(), List.of()), "education_master_excluded"));

        JsonNode preferredTask = tasks("acceptance/v1").stream()
                .filter(t -> "a8-education-master-preferred".equals(t.path("id").asText()))
                .findFirst().orElseThrow();
        var bachelor = List.of(
                AgentMessage.user("本科及以上，硕士优先"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("v105", "verify_boss_job", "{\"jobId\":105}"))),
                AgentMessage.tool("v105", "{\"ok\":true,\"untrusted_data\":{\"jobId\":105,\"captureDepth\":\"FULL\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("s105", "submit_recommendations", "{\"recommendations\":[{\"jobId\":105}]}"))),
                AgentMessage.tool("s105", "{\"ok\":true,\"untrusted_data\":{\"recommendations\":[{\"jobId\":105}]}}"));
        assertTrue(checkNamed(grade(preferredTask, bachelor, List.of(), List.of()), "education_preferred_accepted"));
        assertTrue(checkNamed(grade(preferredTask, bachelor, List.of(), List.of()), "education_master_not_accepted"));
    }

    @Test
    void registeredPreferenceCheckerIsTheActualProductionTool() throws Exception {
        JsonNode task = tasks("acceptance/v1").stream()
                .filter(t -> "a7-education-master-required".equals(t.path("id").asText()))
                .findFirst().orElseThrow();
        Scenario scenario = new Scenario(task);
        var registry = scenario.registry(mock(ConversationStore.class));
        AgentTool checker = registry.find("check_job_preferences").orElseThrow();
        assertEquals(CheckJobPreferencesTool.class, checker.getClass());

        JsonNode listing = read("snapshots/listings/education-pair.json").path("task").path("jobs").get(0);
        JsonNode detail = read("snapshots/details/job-104.json").path("task").path("jobs").get(0);
        JobDescriptionResponse sample = scenario.job(listing, "FULL");
        scenario.jobs.put(104L, new JobDescriptionResponse(
                104L, 1L, sample.title(), sample.companyName(), sample.location(), "实习", "在校生",
                detail.path("description").asText(), detail.path("requirements").asText(), "BOSS",
                sample.sourceUrl(), sample.sourceJobId(), null, null, "FULL", null, null));
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 1L);
        JsonNode result = JSON.valueToTree(checker.execute(JSON.readTree("{\"jobId\":104}"), new AgentToolContext(user)));
        assertEquals(104L, result.path("jobId").asLong());
        assertEquals("FULL", result.path("captureDepth").asText());
        assertTrue(result.path("findings").has("hardBlock"));
        assertTrue(result.path("findings").has("duration"));
    }

    private static boolean checkNamed(List<Check> checks, String name) {
        return checks.stream().filter(check -> name.equals(check.name())).findFirst().orElseThrow().passed();
    }

    @Test
    void realDeepSeekRunsOnlyWhenExplicitlyRequested() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_AGENT_EVAL")),
                "Only scripted offline tests run in CI; do not call DeepSeek");
        String key = require("AI_API_KEY");
        String endpoint = require("AI_BASE_URL");
        String modelId = require("AI_MODEL");
        String slot = require("AGENT_EVAL_SLOT");
        if (V1_CLOSED) {
            throw new IllegalStateException("v1 已关闭：#109 依据 v1 结果修改了 Agent，正式运行请使用 v2");
        }
        if (!List.of("baseline", "structured").contains(slot)) {
            throw new IllegalArgumentException("AGENT_EVAL_SLOT must be baseline or structured");
        }
        String logText = Files.readString(ROOT.resolve("RUN-LOG.md"));
        if (logText.contains("| " + slot + " | completed |")) {
            throw new IllegalStateException("v1 slot " + slot + " has already been used; create v2");
        }
        AiProperties ai = new AiProperties();
        ai.setApiKey(key);
        ai.setBaseUrl(endpoint);
        ai.setModel(modelId);
        var model = new OpenAiCompatibleAgentModel(new AiClient(ai, JSON));
        String sha = currentCommit();
        Instant time = Instant.now();
        var results = new ArrayList<Map<String, Object>>();
        var failCounts = new LinkedHashMap<String, Integer>();
        for (String name : List.of("STEP_LIMIT", "RULE_VIOLATION", "SNAPSHOT_MISSING", "OTHER")) {
            failCounts.put(name, 0);
        }
        String status = "completed";
        try {
            for (String group : List.of("dev", "acceptance/v1")) {
                for (JsonNode task : tasks(group)) {
                    List<Map<String, Object>> trials = new ArrayList<>();
                    int passed = 0;
                    for (int repetition = 1; repetition <= 3; repetition++) {
                        Trial result = run(task, model);
                        if (result.passed()) passed++;
                        else failCounts.merge(result.category(), 1, Integer::sum);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("repetition", repetition);
                        row.put("passed", result.passed());
                        row.put("category", result.passed() ? null : result.category());
                        row.put("checks", result.checks());
                        row.put("replayTrace", result.trace().stream()
                                .filter(line -> line.contains("未录制条件")).toList());
                        if (!result.passed()) row.put("transcriptSummary", summarize(result.transcript(), result.trace()));
                        trials.add(row);
                    }
                    results.add(Map.of("group", group, "taskId", task.path("id").asText(),
                            "passed", passed, "total", 3, "passRate", passed / 3.0, "trials", trials));
                }
            }
        } catch (Exception ex) {
            status = "interrupted";
            throw ex;
        } finally {
            String fileName = "results/" + time.toString().replace(":", "-") + "-" + slot + ".json";
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("timestampUtc", time.toString());
            report.put("commitSha", sha);
            report.put("targetBusinessSha", "baseline".equals(slot) ? BASELINE : sha);
            report.put("modelId", modelId);
            report.put("slot", slot);
            report.put("status", status);
            report.put("failureCategories", failCounts);
            report.put("tasks", results);
            Path output = ROOT.resolve(fileName);
            Files.createDirectories(output.getParent());
            Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Path log = ROOT.resolve("RUN-LOG.md");
            // A blank line between table rows ends the Markdown table, so only add a newline when one is missing.
            String separator = Files.readString(log).endsWith("\n") ? "" : "\n";
            Files.writeString(log, separator + "| " + time + " | " + sha + " | " + modelId + " | " + slot + " | " + status
                    + " | " + fileName + " |\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.println("Agent eval report: " + output.toAbsolutePath());
        }
    }

    private static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }

    private static String currentCommit() throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "HEAD").directory(ROOT.toFile()).start();
        String sha = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0 || !sha.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("Cannot determine commit SHA");
        }
        return sha;
    }

    private static Trial run(JsonNode task, AgentModel model) throws Exception {
        Scenario scenario = new Scenario(task);
        AppUser user = new AppUser();
        ReflectionTestUtils.setField(user, "id", 1L);
        ConversationStore store = mock(ConversationStore.class);
        AtomicReference<JobSearchContext> search = new AtomicReference<>(JobSearchContext.empty());
        when(store.searchContext(any())).thenAnswer(inv -> search.get());
        when(store.updateSearchContext(any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            UnaryOperator<JobSearchContext> change = inv.getArgument(1);
            return search.updateAndGet(change);
        });
        AgentProperties props = new AgentProperties();
        props.setTurnTimeoutSeconds(120);
        AgentLoop loop = new AgentLoop(model, scenario.registry(store), JSON, props, System::currentTimeMillis,
                new BrowserTaskRegistry(), store);
        var messages = new ArrayList<AgentMessage>();
        var trace = new ArrayList<String>();
        AgentLoop.State state = null;
        String exceptionCode = null;
        List<String> utterances = new ArrayList<>();
        task.path("turns").forEach(value -> utterances.add(value.asText()));
        if (task.hasNonNull("followup")) utterances.add(task.path("followup").asText());
        for (int turn = 0; turn < utterances.size(); turn++) {
            messages.add(AgentMessage.user(utterances.get(turn)));
            var run = new ConversationStore.Run(1L, 1L, "turn-" + turn, "run-" + turn,
                    new TranscriptPolicy(props).normalize(messages, (TranscriptPolicy.Approval) null),
                    null, System.currentTimeMillis());
            var context = new AgentToolContext(user, run);
            try {
                RecordingSink sink = new RecordingSink();
                AgentLoop.Outcome output = loop.run(
                        new TranscriptPolicy(props).normalize(messages, (TranscriptPolicy.Approval) null),
                        null, context, sink, () -> false);
                messages = new ArrayList<>(output.transcript());
                state = output.state();
                trace.addAll(sink.trace());
                for (int browserRound = 0; state == AgentLoop.State.AWAITING_BROWSER; browserRound++) {
                    if (browserRound > 12) throw new IllegalStateException("browser replay did not finish");
                    AgentLoop.BrowserTaskEvent event = sink.browser();
                    JsonNode report = scenario.report(event, trace);
                    var pending = new TranscriptPolicy(props).normalize(messages, event.toolCallId(),
                            call -> ToolEnvelope.error(call.name(), "EVAL_ABANDONED", "replaced by browser result"));
                    var resume = new TranscriptPolicy.BrowserReport(event.toolCallId(), event.taskId(),
                            TranscriptPolicy.BrowserReport.COMPLETED, report);
                    sink = new RecordingSink();
                    output = loop.run(pending, null, context, sink, () -> false, System.currentTimeMillis(),
                            new AgentLoop.TurnOptions(null, List.of(), true, resume));
                    messages = new ArrayList<>(output.transcript());
                    state = output.state();
                    trace.addAll(sink.trace());
                }
            } catch (Exception ex) {
                exceptionCode = ex instanceof BusinessException business ? business.getCode() : ex.getClass().getSimpleName();
                trace.add("evaluation exception: " + exceptionCode + ": " + ex.getMessage());
                state = AgentLoop.State.FAILED;
                break;
            }
        }
        List<Check> checks = grade(task, messages, trace, scenario.replayed);
        String category = category(checks, messages, trace, state, exceptionCode);
        return new Trial(checks.stream().allMatch(Check::passed) && state == AgentLoop.State.COMPLETED,
                checks, category, List.copyOf(messages), List.copyOf(trace));
    }

    private record Check(String name, boolean passed, String reason) {}
    private record Trial(boolean passed, List<Check> checks, String category,
                         List<AgentMessage> transcript, List<String> trace) {}
    private record Invocation(String callId, String name, JsonNode args, JsonNode result, int index, int turn) {}
    /** Effective command issued by the replay collector, not model-supplied raw JSON. */
    private record ReplayCommand(String callId, String city, String experience, String keyword) {}

    private static List<Invocation> invocations(List<AgentMessage> messages) {
        var results = new HashMap<String, JsonNode>();
        for (AgentMessage message : messages) if (AgentMessage.TOOL.equals(message.role())) {
            try { results.put(message.toolCallId(), JSON.readTree(message.content())); }
            catch (Exception ex) { throw new IllegalStateException("invalid tool JSON", ex); }
        }
        var calls = new ArrayList<Invocation>();
        int turn = -1;
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if (AgentMessage.USER.equals(message.role())) turn++;
            if (!message.hasToolCalls()) continue;
            for (AgentMessage.ToolCall call : message.toolCalls()) {
                try { calls.add(new Invocation(call.id(), call.name(), JSON.readTree(call.arguments()),
                        results.get(call.id()), index, turn)); }
                catch (Exception ex) { throw new IllegalStateException("invalid tool arguments", ex); }
            }
        }
        return calls;
    }

    private static List<Check> grade(JsonNode task, List<AgentMessage> transcript, List<String> trace,
                                     List<ReplayCommand> replayed) {
        List<Invocation> calls = invocations(transcript);
        var verdicts = new ArrayList<Check>();
        for (JsonNode entry : task.path("checks")) {
            String check = entry.asText();
            boolean passed;
            String failure;
            switch (check) {
                case "prerequisites" -> {
                    Invocation collect = calls.stream().filter(c -> c.name().equals("collect_boss_jobs")).findFirst().orElse(null);
                    passed = collect != null && calls.stream().anyMatch(c ->
                            c.name().equals("get_resume") && c.index() < collect.index() && ok(c.result()))
                            && calls.stream().anyMatch(c -> c.name().equals("get_job_search_preferences")
                            && c.index() < collect.index() && ok(c.result()));
                    failure = "采集前未成功读取简历正文及求职条件";
                }
                case "inherit_internship" -> {
                    // Only real browser handoffs are considered. Rejected JOB_SEARCH_REPEAT
                    // calls never reach Scenario.report and must not poison the verdict.
                    var arrivals = calls.stream().filter(c -> c.name().equals("collect_boss_jobs")
                            && ok(c.result()) && replayed.stream().anyMatch(command -> command.callId().equals(c.callId())))
                            .toList();
                    passed = arrivals.stream().anyMatch(c -> c.turn() == 0 && replayed.stream().anyMatch(command ->
                            command.callId().equals(c.callId()) && "深圳".equals(command.city())
                                    && "在校生".equals(command.experience())))
                            && arrivals.stream().anyMatch(c -> c.turn() > 0 && replayed.stream().anyMatch(command ->
                            command.callId().equals(c.callId()) && "深圳".equals(command.city())
                                    && "在校生".equals(command.experience())));
                    failure = "没有两轮真正到达采集器且继承 city=深圳、experience=在校生 的成功命令";
                }
                case "stop_login", "stop_first_page" -> {
                    String signal = check.equals("stop_login") ? "BOSS_NOT_LOGGED_IN" : "NO_MORE_RESULTS";
                    Invocation blocked = calls.stream().filter(c -> c.name().equals("collect_boss_jobs")
                            && c.result() != null && signal.equals(c.result().path("untrusted_data")
                                .path(check.equals("stop_login") ? "stopReason" : "finishReason").asText())
                            && (check.equals("stop_login") || c.result().path("untrusted_data").path("pagesRead").asInt() == 1))
                            .findFirst().orElse(null);
                    passed = blocked != null && calls.stream().noneMatch(c -> c != blocked
                            && c.turn() == blocked.turn() && c.index() >= blocked.index()
                            && c.name().equals("collect_boss_jobs"));
                    failure = "遇到 " + signal + " 后仍补搜，或者未观察到指定停止信号";
                }
                case "no_listing_as_full" -> {
                    var verified = new HashSet<Long>();
                    for (Invocation call : calls) if (call.name().equals("verify_boss_job")
                            && ok(call.result()) && "FULL".equals(call.result().path("untrusted_data")
                            .path("captureDepth").asText())) {
                        verified.add(call.result().path("untrusted_data").path("jobId").asLong());
                    }
                    passed = true;
                    for (Invocation call : calls) if (call.name().equals("submit_recommendations")) {
                        // Even a rejected attempt to submit a LISTING-only job is a model rule violation.
                        for (JsonNode proposal : call.args().path("recommendations")) {
                            if (!verified.contains(proposal.path("jobId").asLong())) passed = false;
                        }
                        if (ok(call.result())) {
                            for (JsonNode approved : call.result().path("untrusted_data").path("recommendations")) {
                                if (!verified.contains(approved.path("jobId").asLong())) passed = false;
                            }
                        }
                    }
                    failure = "在未核实 FULL JD 时提交岗位（即使服务端已拒绝）";
                }
                case "submit_accepted" -> {
                    passed = calls.stream().anyMatch(c -> c.name().equals("submit_recommendations")
                            && ok(c.result()) && c.result().path("untrusted_data")
                                    .path("recommendations").size() > 0);
                    failure = "没有经 submit_recommendations 提交并通过至少一条岗位";
                }
                case "replan_offtarget" -> {
                    var searches = calls.stream().filter(c -> c.name().equals("collect_boss_jobs")).toList();
                    boolean offTarget = !searches.isEmpty() && ok(searches.getFirst().result())
                            && searches.getFirst().result().path("untrusted_data").path("jobs").size() > 0;
                    if (offTarget) {
                        for (JsonNode job : searches.getFirst().result().path("untrusted_data").path("jobs")) {
                            String title = job.path("title").asText().toLowerCase(Locale.ROOT);
                            if (title.contains("java") || title.contains("后端") || title.contains("实习")) offTarget = false;
                        }
                    }
                    boolean rankedBeforeRetry = searches.size() >= 2 && calls.stream().anyMatch(c ->
                            c.name().equals("rank_jobs_for_resume")
                                    && c.index() > searches.getFirst().index()
                                    && c.index() < searches.get(1).index());
                    passed = searches.size() >= 2 && offTarget && rankedBeforeRetry
                            && !searches.getFirst().args().path("keyword").asText().strip()
                                .equalsIgnoreCase(searches.get(1).args().path("keyword").asText().strip())
                            && searches.stream().allMatch(c -> !"JOB_SEARCH_BUDGET".equals(
                                    c.result() == null ? "" : c.result().path("error").path("code").asText()));
                    failure = "首轮岗位不对口后没有先排序再换关键词补搜";
                }
                case "education_master_excluded", "education_master_not_accepted" -> {
                    boolean inspectedFull = calls.stream().anyMatch(c -> c.name().equals("verify_boss_job")
                            && c.args().path("jobId").asLong() == 104 && ok(c.result())
                            && "FULL".equals(c.result().path("untrusted_data").path("captureDepth").asText()));
                    boolean evaluated = calls.stream().anyMatch(c -> c.name().equals("check_job_preferences")
                            && c.args().path("jobId").asLong() == 104 && ok(c.result()));
                    boolean approved = calls.stream().filter(c -> c.name().equals("submit_recommendations")
                            && ok(c.result())).anyMatch(c -> {
                        for (JsonNode recommendation : c.result().path("untrusted_data").path("recommendations")) {
                            if (recommendation.path("jobId").asLong() == 104) return true;
                        }
                        return false;
                    });
                    passed = !approved && (check.equals("education_master_not_accepted")
                            || inspectedFull && evaluated);
                    failure = "硕士硬门槛岗位未经真实条件检查，或被正式验收为推荐";
                }
                case "education_preferred_accepted" -> {
                    boolean verified = calls.stream().anyMatch(c -> c.name().equals("verify_boss_job")
                            && c.args().path("jobId").asLong() == 105 && ok(c.result())
                            && "FULL".equals(c.result().path("untrusted_data").path("captureDepth").asText()));
                    boolean approved = calls.stream().filter(c -> c.name().equals("submit_recommendations")
                            && ok(c.result())).anyMatch(c -> {
                        for (JsonNode recommendation : c.result().path("untrusted_data").path("recommendations")) {
                            if (recommendation.path("jobId").asLong() == 105) return true;
                        }
                        return false;
                    });
                    passed = verified && approved;
                    failure = "本科及以上、硕士优先的 FULL JD 未被正式验收";
                }
                case "fallback_trace" -> {
                    passed = trace.stream().anyMatch(item -> item.contains("未录制条件"))
                            && calls.stream().anyMatch(c -> c.name().equals("collect_boss_jobs") && ok(c.result()));
                    failure = "未录制列表条件没有返回通用结果并记录 trace";
                }
                default -> throw new IllegalStateException("unknown check " + check);
            }
            verdicts.add(new Check(check, passed, passed ? "" : failure));
        }
        return verdicts;
    }

    private static boolean ok(JsonNode result) { return result != null && result.path("ok").asBoolean(); }

    private static String category(List<Check> checks, List<AgentMessage> transcript, List<String> trace,
                                   AgentLoop.State state, String exception) {
        if ((exception != null && exception.contains("EVAL_SNAPSHOT_MISSING"))
                || transcript.stream().anyMatch(m -> m.content() != null
                && m.content().contains("EVAL_SNAPSHOT_MISSING"))) return "SNAPSHOT_MISSING";
        if (state == AgentLoop.State.STEP_LIMIT || transcript.stream().anyMatch(m ->
                m.content() != null && m.content().contains("AGENT_STEP_LIMIT"))) return "STEP_LIMIT";
        if (exception != null || state == AgentLoop.State.TIMED_OUT || state == AgentLoop.State.FAILED)
            return "OTHER";
        if (checks.stream().anyMatch(c -> !c.passed()) || state != AgentLoop.State.COMPLETED)
            return "RULE_VIOLATION";
        return "PASS";
    }

    private static List<String> summarize(List<AgentMessage> transcript, List<String> trace) {
        List<String> summary = new ArrayList<>();
        for (Invocation call : invocations(transcript)) {
            String code = call.result() == null ? "pending" : call.result().path("error").path("code").asText(
                    call.result().path("ok").asBoolean() ? "ok" : "unknown");
            summary.add("turn=" + call.turn() + " " + call.name() + " " + call.args() + " => " + code);
        }
        trace.stream().filter(line -> line.contains("未录制条件") || line.contains("exception"))
                .forEach(summary::add);
        String last = transcript.stream().filter(m -> AgentMessage.ASSISTANT.equals(m.role())
                && m.content() != null).reduce((one, two) -> two).map(AgentMessage::content).orElse("");
        summary.add("final=" + last.substring(0, Math.min(250, last.length())));
        return summary;
    }

    private static final class RecordingSink implements AgentEventSink {
        private final List<String> events = new ArrayList<>();
        private AgentLoop.BrowserTaskEvent browser;
        @Override public void emit(String name, Object event) {
            events.add(name);
            if ("browser_task_required".equals(name)) browser = (AgentLoop.BrowserTaskEvent) event;
        }
        AgentLoop.BrowserTaskEvent browser() {
            if (browser == null) throw new IllegalStateException("missing browser_task_required");
            return browser;
        }
        List<String> trace() { return List.copyOf(events); }
    }

    private static final class Scenario {
        private final JsonNode task;
        private final JsonNode profile;
        private final List<ReplayCommand> replayed = new ArrayList<>();
        private final Map<Long, JobDescriptionResponse> jobs = new LinkedHashMap<>();
        private final JobDescriptionService jobService = mock(JobDescriptionService.class);
        private final JobSearchPreferencesService preferences = mock(JobSearchPreferencesService.class);
        Scenario(JsonNode task) throws Exception {
            this.task = task;
            this.profile = read("profile.json");
            when(jobService.findById(anyLong())).thenAnswer(inv -> {
                long id = inv.getArgument(0);
                JobDescriptionResponse job = jobs.get(id);
                if (job == null) throw new ResourceNotFoundException("JobDescription", id);
                return job;
            });
            // Baseline view ignores unknown structured fields; a later version can consume them.
            JobSearchPreferencesView view = JSON.copy()
                    .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .treeToValue(profile.path("preferences"), JobSearchPreferencesView.class);
            when(preferences.forUser(anyLong())).thenReturn(view);
        }

        JsonNode report(AgentLoop.BrowserTaskEvent event, List<String> trace) throws Exception {
            if ("collect_boss_jobs".equals(event.tool())) {
                replayed.add(new ReplayCommand(event.toolCallId(),
                        Objects.toString(event.command().get("city"), ""),
                        Objects.toString(event.command().get("experience"), ""),
                        Objects.toString(event.command().get("keyword"), "")));
                trace.add("collector command: callId=" + event.toolCallId() + ",city="
                        + event.command().get("city") + ",experience=" + event.command().get("experience"));
                String keyword = Objects.toString(event.command().get("keyword"), "").strip()
                        .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                String city = Objects.toString(event.command().get("city"), "");
                String experience = Objects.toString(event.command().get("experience"), "");
                String matched = null;
                if ("深圳".equals(city) && "在校生".equals(experience)) {
                    outer: for (JsonNode search : task.path("searches")) {
                        for (JsonNode word : search.path("keywords")) {
                            if (keyword.equals(word.asText().strip().toLowerCase(Locale.ROOT))) {
                                matched = search.path("snapshot").asText();
                                break outer;
                            }
                        }
                    }
                }
                if (matched == null) {
                    matched = "generic-unrecorded";
                    trace.add("未录制条件: keyword=" + keyword + ",city=" + city + ",experience=" + experience);
                }
                ObjectNode report = (ObjectNode) read("snapshots/listings/" + matched + ".json").deepCopy();
                ObjectNode command = JSON.valueToTree(event.command());
                report.with("task").set("command", command);
                return report;
            }
            if ("verify_boss_job".equals(event.tool())) {
                long id = Long.parseLong(event.command().get("jobId").toString());
                if (!containsDetail(id)) throw new BusinessException("EVAL_SNAPSHOT_MISSING", "未录制详情 jobId=" + id);
                return read("snapshots/details/job-" + id + ".json");
            }
            throw new IllegalStateException("unexpected browser tool " + event.tool());
        }

        private boolean containsDetail(long id) {
            for (JsonNode detail : task.path("details")) if (detail.asLong() == id) return true;
            return false;
        }

        private JobDescriptionResponse job(JsonNode row, String depth) {
            return new JobDescriptionResponse(row.path("evalJobId").asLong(), 1L,
                    row.path("title").asText(), row.path("companyName").asText(), row.path("location").asText(),
                    row.path("employmentType").asText(), "在校生", row.path("description").asText(),
                    row.path("requirements").asText(), "BOSS", row.path("sourceUrl").asText(),
                    row.path("sourceJobId").asText(), null, null, depth, null, null);
        }

        AgentToolRegistry registry(ConversationStore store) {
            List<AgentTool> tools = new ArrayList<>();
            tools.add(new AgentTool() {
                public String name() { return "list_resumes"; }
                public String description() { return "列出当前用户上传的简历及方向，先选择合适的一份再读正文"; }
                public ToolSchema parameters() { return ToolSchema.object(); }
                public Object execute(JsonNode args, AgentToolContext ctx) {
                    return Map.of("total", 1, "page", 0, "totalPages", 1,
                            "resumes", List.of(profile.path("resume")));
                }
            });
            tools.add(new AgentTool() {
                public String name() { return "get_resume"; }
                public String description() { return "读取选定简历正文；找工作必须先查看简历真实内容"; }
                public ToolSchema parameters() { return ToolSchema.object().integer("resumeId", "简历 id，只有一份时可省略", false, 1, Long.MAX_VALUE); }
                public Object execute(JsonNode args, AgentToolContext ctx) {
                    return profile.path("resume");
                }
            });
            tools.add(new AgentTool() {
                public String name() { return "get_job_search_preferences"; }
                public String description() { return "先读用户填写的求职条件，包括学历、在校状态、毕业年月、工作年数、期望城市和实习时间"; }
                public ToolSchema parameters() { return ToolSchema.object(); }
                public Object execute(JsonNode args, AgentToolContext ctx) {
                    return profile.path("preferences");
                }
            });
            tools.add(new DeferrableTool() {
                final CollectBossJobsTool prototype = new CollectBossJobsTool(null, null, null);
                public String name() { return "collect_boss_jobs"; }
                public String description() { return prototype.description(); }
                public ToolSchema parameters() { return prototype.parameters(); }
                public Begin begin(JsonNode args, AgentToolContext ctx) {
                    return new InBrowser("eval-replay", "collectBossJobs",
                            JSON.convertValue(args, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
                }
                public Object resume(JsonNode args, JsonNode report, AgentToolContext ctx) {
                    JsonNode task = report.path("task");
                    var ids = new ArrayList<Long>();
                    var visible = new ArrayList<Map<String, Object>>();
                    for (JsonNode row : task.path("jobs")) {
                        var saved = job(row, "LISTING");
                        jobs.putIfAbsent(saved.id(), saved);
                        ids.add(saved.id());
                        visible.add(Map.of("jobId", saved.id(), "title", saved.title(),
                                "companyName", saved.companyName(), "location", saved.location()));
                    }
                    String status = task.path("status").asText();
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("status", status.equals("completed") ? "COMPLETED" : "STOPPED");
                    data.put("pagesRead", task.path("pagesRead").asInt());
                    data.put("dailyPagesLeft", task.path("dailyPagesLeft").asInt(24));
                    data.put("jobIds", ids);
                    data.put("jobs", visible);
                    if (status.equals("completed")) data.put("finishReason", task.path("finishReason").asText());
                    else data.put("stopReason", task.path("stopReason").asText());
                    return data;
                }
            });
            tools.add(new AgentTool() {
                public String name() { return "rank_jobs_for_resume"; }
                public String description() { return "粗排岗位，similarity 仅为排序相似度，不是匹配分，不能把列表摘要视为完整 JD"; }
                public ToolSchema parameters() {
                    return ToolSchema.object().integer("resumeId", "简历 id", true, 1, Long.MAX_VALUE)
                            .integer("limit", "返回条数", false, 1, 10)
                            .integerArray("jobIds", "待排序的岗位 id", false, 1, Long.MAX_VALUE, 45);
                }
                public Object execute(JsonNode args, AgentToolContext ctx) {
                    var candidates = new ArrayList<JobDescriptionResponse>();
                    if (args.path("jobIds").isArray()) {
                        args.path("jobIds").forEach(value -> {
                            JobDescriptionResponse entry = jobs.get(value.asLong());
                            if (entry != null) candidates.add(entry);
                        });
                    } else candidates.addAll(jobs.values());
                    candidates.sort(Comparator.comparingDouble((JobDescriptionResponse entry) ->
                            entry.title().contains("Java") || entry.title().contains("后端") ? 0.85 : 0.12).reversed());
                    return Map.of("resumeId", 1, "matches", candidates.stream()
                            .limit(args.path("limit").asInt(5))
                            .map(entry -> Map.of("jobId", entry.id(), "title", entry.title(),
                                    "companyName", entry.companyName(),
                                    "similarity", entry.title().contains("Java") || entry.title().contains("后端") ? 0.85 : 0.12))
                            .toList());
                }
            });
            tools.add(new DeferrableTool() {
                final VerifyBossJobTool prototype = new VerifyBossJobTool(jobService, null);
                public String name() { return "verify_boss_job"; }
                public String description() { return prototype.description(); }
                public ToolSchema parameters() { return prototype.parameters(); }
                public Done beforeBudget(JsonNode args) {
                    long id = args.path("jobId").asLong();
                    if (!jobs.containsKey(id) || !containsDetail(id))
                        throw new BusinessException("EVAL_SNAPSHOT_MISSING", "未录制详情 jobId=" + id);
                    if ("FULL".equals(jobs.get(id).captureDepth())) return new Done(Map.of(
                            "jobId", id, "captureDepth", "FULL", "verificationStatus", "ALREADY_FULL"));
                    return null;
                }
                public Begin begin(JsonNode args, AgentToolContext ctx) {
                    long id = args.path("jobId").asLong();
                    return new InBrowser("eval-replay", "verifyBossJob", Map.of("jobId", id));
                }
                public Object resume(JsonNode args, JsonNode report, AgentToolContext ctx) {
                    long id = args.path("jobId").asLong();
                    JsonNode full = report.path("task").path("jobs").path(0);
                    JobDescriptionResponse old = jobs.get(id);
                    JobDescriptionResponse updated = new JobDescriptionResponse(id, 1L, old.title(),
                            old.companyName(), old.location(), "实习", "在校生",
                            full.path("description").asText(), full.path("requirements").asText(), "BOSS",
                            old.sourceUrl(), old.sourceJobId(), null, null, "FULL", null, null);
                    jobs.put(id, updated);
                    return Map.of("jobId", id, "captureDepth", "FULL", "verificationStatus", "SAVED_FULL");
                }
            });
            tools.add(new GetJobTool(jobService));
            // Real production matcher is exercised; only its data dependencies are mocked.
            tools.add(new CheckJobPreferencesTool(jobService, preferences));
            tools.add(new AgentTool() {
                public String name() { return "update_job_search_context"; }
                public String description() { return "修改或清空会话搜索条件，缺省字段继承当前值"; }
                public ToolSchema parameters() { return ToolSchema.object()
                        .string("keyword", "方向", false, 50).string("city", "城市", false, 10)
                        .string("jobType", "实习/全职/不限", false, 10)
                        .string("experience", "经验", false, 10); }
                public Object execute(JsonNode args, AgentToolContext ctx) {
                    return store.updateSearchContext(ctx.run(), current -> current.patch(args));
                }
            });
            tools.add(new SubmitRecommendationsTool(jobService, preferences));
            return new AgentToolRegistry(tools);
        }
    }
}
