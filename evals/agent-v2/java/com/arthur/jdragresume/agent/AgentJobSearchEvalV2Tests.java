package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.AgentEvalV2Grading.Check;
import com.arthur.jdragresume.agent.AgentEvalV2.Group;
import com.arthur.jdragresume.agent.AgentEvalV2Grading.Library;
import com.arthur.jdragresume.agent.AgentEvalV2.Trial;
import com.arthur.jdragresume.agent.tool.CheckJobPreferencesTool;
import com.arthur.jdragresume.agent.tool.CollectBossJobsTool;
import com.arthur.jdragresume.agent.tool.GetJobTool;
import com.arthur.jdragresume.agent.tool.SubmitRecommendationsTool;
import com.arthur.jdragresume.agent.tool.VerifyBossJobTool;
import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.ai.AiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static com.arthur.jdragresume.agent.AgentEvalV2.JSON;
import static com.arthur.jdragresume.agent.AgentEvalV2.ROOT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Agent job-search evaluation v2. CI runs only the offline checks below; the real model runs only
 * through {@link #realModelRunsOnlyWhenExplicitlyRequested} with RUN_AGENT_EVAL=true (see evals/agent-v2/PROTOCOL.md).
 */
class AgentJobSearchEvalV2Tests {
    /** Business code under test for the baseline slot: main after #109, before the education/experience PR. */
    static final String BASELINE = "c1c80c5ace3314de34949fd74e061dea8bb20990";
    /** Frozen acceptance inputs. Changing any acceptance task or snapshot changes this value; see PROTOCOL.md. */
    static final String ACCEPTANCE_DIGEST = "0b11fd025bc58d6bd130af5883419e020705c70f627915908cc9020cd7e9d351";
    static final List<String> SLOTS = List.of("baseline", "structured");
    private static final int REPETITIONS = 3;
    private static final Set<String> CHECK_TYPES = Set.of("prerequisites", "inherit_filters", "stop_on_signal",
            "no_listing_as_full", "submit_accepted", "replan_offtarget", "gate_excluded", "preferred_accepted");

    @Test
    void tasksAreWellFormedDisjointAndCoverEveryRule() throws Exception {
        Map<String, String> owners = new HashMap<>();
        Set<String> taskIds = new HashSet<>();
        for (String name : AgentEvalV2.GROUPS) {
            Group group = AgentEvalV2.group(name);
            assertFalse(group.tasks().isEmpty(), name);
            Path listings = ROOT.resolve("snapshots/" + name + "/listings");
            Map<String, JsonNode> rows = new HashMap<>();
            try (Stream<Path> files = Files.list(listings)) {
                for (Path file : files.toList()) {
                    JsonNode task = JSON.readTree(file.toFile()).path("task");
                    for (JsonNode row : task.path("jobs")) {
                        String id = row.path("sourceJobId").asText();
                        assertNull(owners.put(id, name + "/" + file.getFileName()), "sourceJobId reused: " + id);
                        rows.put(id, row);
                        boolean halts = "NO_MORE_RESULTS".equals(task.path("finishReason").asText());
                        if (!halts && row.path("evalSimilarity").asDouble() >= AgentEvalV2Grading.ON_TARGET) {
                            assertTrue(Files.exists(ROOT.resolve("snapshots/" + name + "/details/" + id + ".json")),
                                    "on-target listing row without a detail snapshot: " + id);
                        }
                    }
                }
            }
            assertTrue(AgentEvalV2.read("snapshots/" + name + "/listings/" + AgentEvalV2.GENERIC_LISTING + ".json")
                    .path("task").path("unrecordedCondition").asBoolean(), name + " needs a generic unrecorded listing");
            Set<String> covered = new TreeSet<>();
            for (JsonNode task : group.tasks()) {
                String id = task.path("id").asText();
                assertTrue(taskIds.add(id), "task id used twice (dev and acceptance must not share tasks): " + id);
                int turns = task.path("turns").size();
                assertTrue(turns >= 1 && turns <= 4, id + ": 1-4 turns keep every tool result uncompacted");
                assertEquals(turns, task.path("reference").size(), id + ": one reference list per turn");
                Set<String> listed = new HashSet<>();
                for (JsonNode search : task.path("searches")) {
                    String file = "snapshots/" + name + "/listings/" + search.path("snapshot").asText() + ".json";
                    assertTrue(Files.exists(ROOT.resolve(file)), id + ": missing " + file);
                    AgentEvalV2.read(file).path("task").path("jobs").forEach(row -> listed.add(row.path("sourceJobId").asText()));
                    for (JsonNode set : search.path("keywordSets")) {
                        assertTrue(group.keywordSets().has(set.asText()), id + ": unknown keyword set " + set);
                    }
                }
                for (JsonNode check : task.path("checks")) {
                    String type = check.path("type").asText();
                    assertTrue(CHECK_TYPES.contains(type), id + ": unknown check " + type);
                    switch (type) {
                        case "inherit_filters" -> assertTrue(check.hasNonNull("city") && check.hasNonNull("experience"), id);
                        case "stop_on_signal" -> assertTrue(Set.of("BOSS_NOT_LOGGED_IN", "NO_MORE_RESULTS")
                                .contains(check.path("signal").asText()), id);
                        case "gate_excluded", "preferred_accepted" -> {
                            String job = check.path("job").asText();
                            assertTrue(Set.of("education", "experience").contains(check.path("rule").asText()), id);
                            assertTrue(listed.contains(job), id + ": checked job is not in the task's listings: " + job);
                            assertTrue(rows.get(job).path("evalSimilarity").asDouble() >= AgentEvalV2Grading.ON_TARGET,
                                    id + ": the gated job must rank on target so a careful agent inspects it");
                        }
                        default -> { }
                    }
                    covered.add(AgentEvalV2Grading.rule(check));
                }
            }
            assertTrue(covered.containsAll(AgentEvalV2Grading.RULES), name + " misses rules: "
                    + AgentEvalV2Grading.RULES.stream().filter(rule -> !covered.contains(rule)).toList());
        }
    }

    @Test
    void acceptanceInputsAreFrozen() throws Exception {
        assertEquals(ACCEPTANCE_DIGEST, AgentEvalV2.acceptanceDigest(),
                "acceptance 任务或快照被修改；v2 冻结后不得改动，需要改变时建立 v3（见 PROTOCOL.md）");
    }

    @Test
    void referenceSolutionsPassEveryDevTaskThroughTheRealLoop() throws Exception {
        Group dev = AgentEvalV2.group("dev");
        for (JsonNode task : dev.tasks()) {
            Trial trial = reference(dev, task);
            assertEquals("PASS", trial.category(), task.path("id").asText() + " " + trial.checks()
                    + "\n" + String.join("\n", AgentEvalV2.summarize(trial)));
        }
    }

    /**
     * Acceptance outcomes depend on the business code under test, so CI only checks that the harness replays
     * every acceptance reference without errors; whether they pass is recorded per formal run (referencePassed).
     */
    @Test
    void referenceSolutionsReplayEveryAcceptanceTask() throws Exception {
        Group acceptance = AgentEvalV2.group("acceptance");
        for (JsonNode task : acceptance.tasks()) {
            Trial trial = reference(acceptance, task);
            assertEquals(task.path("checks").size(), trial.checks().size());
            assertTrue(trial.trace().stream().noneMatch(line -> line.startsWith("evaluation exception:")),
                    task.path("id").asText() + " 回放参考解时评测框架抛出异常");        }
    }

    @Test
    void scriptedModelChecksTheEvaluationPipeline() throws Exception {
        for (String name : AgentEvalV2.GROUPS) {
            Group group = AgentEvalV2.group(name);
            for (JsonNode task : group.tasks()) {
                int passed = 0;
                for (int trial = 0; trial < REPETITIONS; trial++) {
                    Trial result = AgentEvalV2.run(group, task, scenario -> new ScriptedAgentModel(JSON));
                    assertEquals(task.path("checks").size(), result.checks().size());
                    assertFalse(result.transcript().isEmpty());
                    assertTrue(result.category().equals("PASS") || AgentEvalV2Grading.FAILURE_CATEGORIES.contains(result.category()));
                    assertTrue(result.trace().stream().noneMatch(line -> line.startsWith("evaluation exception:")),
                            "评测框架抛出异常: " + task.path("id").asText() + " " + result.trace());
                    if (stream(task.path("checks")).anyMatch(check -> "stop_on_signal".equals(check.path("type").asText()))) {
                        assertTrue(result.trace().contains("browser_task_required"), "停止任务必须真正执行一次快照浏览器回放");
                    }
                    if (result.passed()) passed++;
                }
                System.out.println("Agent v2 scripted eval " + name + "/" + task.path("id").asText()
                        + ": " + passed + "/" + REPETITIONS);
            }
        }
    }

    /**
     * Each mutation breaks one rule in an otherwise passing dev reference run, and that check must turn red.
     * Only violations whose outcome does not depend on the business code under test are mutated here; the
     * gate checks are covered by {@link #gateChecksUseTheAcceptedSubmissionAndTheFullJd}.
     */
    @Test
    void checksRejectRuleViolationsRunThroughTheRealTools() throws Exception {
        Group dev = AgentEvalV2.group("dev");
        assertViolation(dev, "d01-prereq-verified-submit", "prerequisites", turns -> turns.get(0).insert(2,
                step("collect_boss_jobs", "{\"keyword\":\"AI 应用开发\",\"city\":\"杭州\",\"jobType\":\"实习\",\"maxPages\":1}")));
        assertViolation(dev, "d01-prereq-verified-submit", "no_listing_as_full", turns -> ((ArrayNode) last(turns.get(0))
                .path("args").path("recommendations")).add(JSON.readTree(
                "{\"jobId\":\"$job:d2-hz-llm-02\",\"reason\":\"方向对口\",\"evidence\":\"基于开源大模型开发数据分析助手\"}")));
        assertViolation(dev, "d01-prereq-verified-submit", "submit_accepted", turns -> turns.get(0).remove(turns.get(0).size() - 1));
        assertViolation(dev, "d02-inherit-two-turns", "inherit_filters",
                turns -> ((ObjectNode) turns.get(1).get(0).path("args")).put("city", "上海"));
        assertViolation(dev, "d03-stop-login", "stop_on_signal:BOSS_NOT_LOGGED_IN", turns -> turns.get(0).add(
                step("collect_boss_jobs", "{\"keyword\":\"大模型应用\",\"maxPages\":1}")));
        assertViolation(dev, "d04-stop-first-page", "stop_on_signal:NO_MORE_RESULTS", turns -> turns.get(0).add(
                step("collect_boss_jobs", "{\"keyword\":\"RAG\",\"maxPages\":1}")));
        assertViolation(dev, "d05-replan-offtarget", "replan_offtarget", turns -> turns.get(0).remove(4));
        assertViolation(dev, "d07-education-preferred", "preferred_accepted:education", turns -> turns.get(0).remove(turns.get(0).size() - 1));
        assertViolation(dev, "d09-experience-preferred", "preferred_accepted:experience", turns -> turns.get(0).remove(turns.get(0).size() - 1));
        // A gate that was never inspected in full does not count as excluded.
        assertViolation(dev, "d06-education-required", "gate_excluded:education", turns -> {
            turns.get(0).remove(6);
            turns.get(0).remove(5);
        });
    }

    @Test
    void gateChecksUseTheAcceptedSubmissionAndTheFullJd() throws Exception {
        JsonNode task = JSON.readTree("""
                {"checks":[{"type":"gate_excluded","rule":"education","job":"gate"},
                           {"type":"preferred_accepted","rule":"experience","job":"preferred"}]}""");
        Library library = new Library(Map.of("gate", 104L, "preferred", 105L), Map.of());
        List<AgentMessage> verified = List.of(
                AgentMessage.user("找实习"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("v1", "verify_boss_job", "{\"jobId\":104}"))),
                AgentMessage.tool("v1", "{\"ok\":true,\"tool\":\"verify_boss_job\",\"untrusted_data\":{\"jobId\":104,\"captureDepth\":\"FULL\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("v2", "verify_boss_job", "{\"jobId\":105}"))),
                AgentMessage.tool("v2", "{\"ok\":true,\"tool\":\"verify_boss_job\",\"untrusted_data\":{\"jobId\":105,\"captureDepth\":\"FULL\"}}"));
        List<Check> correct = AgentEvalV2Grading.grade(task, submitted(verified, "[105]", "[104]"), List.of(), library);
        assertTrue(correct.get(0).passed(), "a gate the server rejected is excluded");
        assertTrue(correct.get(1).passed());
        List<Check> wrong = AgentEvalV2Grading.grade(task, submitted(verified, "[104]", "[105]"), List.of(), library);
        assertFalse(wrong.get(0).passed(), "an accepted gate job is a violation");
        assertFalse(wrong.get(1).passed(), "a rejected preferred job is a violation");
        List<Check> claimed = AgentEvalV2Grading.grade(task, List.of(AgentMessage.user("找实习"),
                AgentMessage.assistant("正式推荐 105 号岗位", null)), List.of(), library);
        assertFalse(claimed.get(0).passed(), "a gate never inspected in full is not shown to be excluded");
        assertFalse(claimed.get(1).passed(), "a recommendation claimed in prose is not an accepted submission");
    }

    private static List<AgentMessage> submitted(List<AgentMessage> before, String accepted, String rejected) throws Exception {
        List<AgentMessage> messages = new ArrayList<>(before);
        messages.add(AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("s1", "submit_recommendations",
                "{\"recommendations\":[{\"jobId\":104},{\"jobId\":105}]}"))));
        ObjectNode result = JSON.createObjectNode().put("ok", true).put("tool", "submit_recommendations");
        ObjectNode data = result.putObject("untrusted_data");
        ArrayNode passed = data.putArray("recommendations");
        JSON.readTree(accepted).forEach(id -> passed.addObject().put("jobId", id.asLong()));
        ArrayNode failed = data.putArray("rejected");
        JSON.readTree(rejected).forEach(id -> failed.addObject().put("jobId", id.asLong()));
        messages.add(AgentMessage.tool("s1", result.toString()));
        return messages;
    }

    @Test
    void failureCategoriesSeparatePlanProtocolErrors() throws Exception {
        List<Check> passing = List.of(new Check("submit_accepted", true, ""));
        List<Check> failing = List.of(new Check("submit_accepted", false, "x"));
        List<AgentMessage> transcript = List.of(AgentMessage.user("找杭州实习"), AgentMessage.assistant("好的", null));
        var completed = List.of(AgentLoop.State.COMPLETED);
        var failed = List.of(AgentLoop.State.FAILED);
        assertEquals("PASS", AgentEvalV2Grading.category(passing, transcript, completed, List.of(), null));
        assertEquals("RULE_VIOLATION", AgentEvalV2Grading.category(failing, transcript, completed, List.of(), null));
        assertEquals("STEP_LIMIT", AgentEvalV2Grading.category(failing, transcript, List.of(AgentLoop.State.STEP_LIMIT),
                List.of("AGENT_STEP_LIMIT"), null));
        assertEquals("SNAPSHOT_MISSING", AgentEvalV2Grading.category(failing, transcript, failed, List.of(), "EVAL_SNAPSHOT_MISSING"));
        for (String code : List.of("AGENT_PLAN_INVALID", "AGENT_PLAN_MISMATCH", "AGENT_PLAN_INCOMPLETE")) {
            assertEquals("PLAN_PROTOCOL", AgentEvalV2Grading.category(failing, transcript, failed, List.of(code), null), code);
        }
        assertEquals("OTHER", AgentEvalV2Grading.category(failing, transcript, failed, List.of("AGENT_TIMEOUT"), null));
        assertEquals("OTHER", AgentEvalV2Grading.category(failing, transcript, failed, List.of(), "IllegalStateException"));
        // AGENT_REPLAN_LIMIT does not carry the AGENT_PLAN_ prefix and stays a rule violation, as in v1.
        assertEquals("RULE_VIOLATION", AgentEvalV2Grading.category(failing, transcript, List.of(AgentLoop.State.REPLAN_LIMIT),
                List.of("AGENT_REPLAN_LIMIT"), null));
        // A later completed turn does not hide an earlier failed one.
        assertEquals("OTHER", AgentEvalV2Grading.category(passing, transcript,
                List.of(AgentLoop.State.FAILED, AgentLoop.State.COMPLETED), List.of("AGENT_TIMEOUT"), null));

        // End to end: a model that keeps sending malformed plans ends the turn with AGENT_PLAN_INVALID.
        Group dev = AgentEvalV2.group("dev");
        Trial trial = AgentEvalV2.run(dev, dev.task("d01-prereq-verified-submit"), scenario ->
                (system, messages, tools, timeout) -> AgentModel.Reply.call(
                        AgentMessage.ToolCall.of("plan_" + messages.size(), AgentPlan.TOOL, "{}")));
        assertEquals("PLAN_PROTOCOL", trial.category(), trial.errorCodes().toString());
        assertTrue(trial.errorCodes().contains("AGENT_PLAN_INVALID"));
    }

    @Test
    void unrecordedConditionsReplayGenericListingsAndUnrecordedDetailsFailExplicitly() throws Exception {
        Group dev = AgentEvalV2.group("dev");
        ObjectNode task = (ObjectNode) dev.task("d01-prereq-verified-submit").deepCopy();
        task.set("reference", JSON.readTree("""
                [[{"tool":"get_resume","args":{"resumeId":"$resume"}},
                  {"tool":"get_job_search_preferences","args":{}},
                  {"tool":"collect_boss_jobs","args":{"keyword":"门店导购","city":"杭州","jobType":"实习","maxPages":1}},
                  {"tool":"verify_boss_job","args":{"jobId":"$job:d2-gen-01"}}]]"""));
        Trial trial = reference(dev, task);
        assertTrue(trial.trace().stream().anyMatch(line -> line.startsWith("未录制条件: keyword=门店导购")), trial.trace().toString());
        assertEquals("SNAPSHOT_MISSING", trial.category());
    }

    @Test
    void graderJudgesTheEffectiveCollectorCommandNotRawArguments() throws Exception {
        JsonNode task = JSON.readTree("{\"checks\":[{\"type\":\"inherit_filters\",\"city\":\"广州\",\"experience\":\"在校生\"}]}");
        List<AgentMessage> messages = List.of(
                AgentMessage.user("找广州前端实习"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c1", "collect_boss_jobs",
                        "{\"keyword\":\"前端\",\"city\":\"广州\",\"jobType\":\"实习\"}"))),
                AgentMessage.tool("c1", "{\"ok\":true,\"tool\":\"collect_boss_jobs\",\"untrusted_data\":{\"jobIds\":[101]}}"),
                AgentMessage.user("换成 Vue 再找找"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c2", "collect_boss_jobs", "{\"keyword\":\"前端\"}"))),
                AgentMessage.tool("c2", "{\"ok\":false,\"tool\":\"collect_boss_jobs\",\"error\":{\"code\":\"JOB_SEARCH_REPEAT\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c3", "collect_boss_jobs", "{\"keyword\":\"Vue\"}"))),
                AgentMessage.tool("c3", "{\"ok\":true,\"tool\":\"collect_boss_jobs\",\"untrusted_data\":{\"jobIds\":[102]}}"));
        var inherited = List.of(new AgentEvalV2Grading.ReplayCommand("c1", 0, "广州", "在校生", "前端"),
                new AgentEvalV2Grading.ReplayCommand("c3", 1, "广州", "在校生", "Vue"));
        assertTrue(AgentEvalV2Grading.grade(task, messages, inherited, Library.empty()).getFirst().passed());
        var widened = List.of(inherited.getFirst(), new AgentEvalV2Grading.ReplayCommand("c3", 1, "广州", "不限", "Vue"));
        assertFalse(AgentEvalV2Grading.grade(task, messages, widened, Library.empty()).getFirst().passed());
        assertFalse(AgentEvalV2Grading.grade(task, messages, List.of(inherited.getFirst()), Library.empty()).getFirst().passed(),
                "a later turn without any collector arrival cannot show inheritance");
    }

    @Test
    void registeredToolsAreTheProductionImplementations() throws Exception {
        Group dev = AgentEvalV2.group("dev");
        var registry = new AgentEvalV2.Scenario(dev, dev.tasks().getFirst()).registry(mock(ConversationStore.class));
        assertEquals(CollectBossJobsTool.class, registry.find("collect_boss_jobs").orElseThrow().getClass());
        assertEquals(VerifyBossJobTool.class, registry.find("verify_boss_job").orElseThrow().getClass());
        assertEquals(GetJobTool.class, registry.find("get_job").orElseThrow().getClass());
        assertEquals(CheckJobPreferencesTool.class, registry.find("check_job_preferences").orElseThrow().getClass());
        assertEquals(SubmitRecommendationsTool.class, registry.find("submit_recommendations").orElseThrow().getClass());
    }

    @Test
    void runSlotsArePreRegisteredAndSingleUse() {
        assertThrows(IllegalArgumentException.class, () -> requireRunnable("rerun", ""));
        assertDoesNotThrow(() -> requireRunnable("baseline", "| 2026-11-01T00:00:00Z | abc | m | baseline | interrupted | x |\n"));
        assertThrows(IllegalStateException.class, () ->
                requireRunnable("baseline", "| 2026-11-01T00:00:00Z | abc | m | baseline | completed | x |\n"));
        assertDoesNotThrow(() -> requireRunnable("structured", "| 2026-11-01T00:00:00Z | abc | m | baseline | completed | x |\n"));
    }

    @Test
    void everyProfileFieldReachesTheBusinessPreferences() throws Exception {
        // Wiring only: the profile keeps its frozen field names; each one must land in the business DTO.
        for (String name : AgentEvalV2.GROUPS) {
            var view = AgentEvalV2.preferencesView(AgentEvalV2.group(name).profile().path("preferences"));
            assertNotNull(view.educationLevel(), name + ": education");
            assertNotNull(view.currentlyStudent(), name + ": studentStatus");
            assertNotNull(view.expectedGraduationDate(), name + ": expectedGraduation");
            assertNotNull(view.workExperienceYears(), name + ": workExperienceYears");
        }
        var dev = AgentEvalV2.preferencesView(AgentEvalV2.group("dev").profile().path("preferences"));
        assertEquals(2, dev.educationLevel());
        assertEquals(Boolean.TRUE, dev.currentlyStudent());
        assertEquals(2027, dev.expectedGraduationDate().getYear());
        assertEquals(0, dev.workExperienceYears());
    }

    @Test
    void providerFailuresAbortTheRunInsteadOfBeingScored() throws Exception {
        Group dev = AgentEvalV2.group("dev");
        JsonNode task = dev.tasks().getFirst();
        for (String code : List.of("AI_BALANCE_INSUFFICIENT", "AI_AUTH_FAILED", "AI_RATE_LIMITED", "AI_SERVICE_UNAVAILABLE")) {
            Trial trial = AgentEvalV2.run(dev, task, scenario -> (system, transcript, tools, timeout) -> {
                throw new com.arthur.jdragresume.exception.BusinessException(code, "provider failure");
            });
            assertEquals(Optional.of(code), providerFailure(trial), trial.errorCodes() + " " + trial.trace());
        }
        // A model that simply answers badly is scored, not treated as an outage.
        Trial scored = AgentEvalV2.run(dev, task, scenario -> (system, transcript, tools, timeout) ->
                AgentModel.Reply.answer("不查了"));
        assertEquals(Optional.empty(), providerFailure(scored));
    }

    @Test
    void realModelRunsOnlyWhenExplicitlyRequested() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("RUN_AGENT_EVAL")),
                "Only offline scripted checks run in CI; the real model is never called");
        String key = require("AI_API_KEY");
        String endpoint = require("AI_BASE_URL");
        String modelId = require("AI_MODEL");
        String slot = require("AGENT_EVAL_SLOT");
        requireRunnable(slot, Files.readString(ROOT.resolve("RUN-LOG.md")));
        assertEquals(ACCEPTANCE_DIGEST, AgentEvalV2.acceptanceDigest(), "acceptance 输入与冻结版本不一致，拒绝正式运行");
        if ("baseline".equals(slot)) requireBaselineBusinessCode();
        AiProperties ai = new AiProperties();
        ai.setApiKey(key);
        ai.setBaseUrl(endpoint);
        ai.setModel(modelId);
        var model = new OpenAiCompatibleAgentModel(new AiClient(ai, JSON));
        String sha = git("rev-parse", "HEAD").strip();
        if (!sha.matches("[0-9a-f]{40}")) throw new IllegalStateException("Cannot determine commit SHA");
        Instant time = Instant.now();
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Integer> failures = new LinkedHashMap<>();
        AgentEvalV2Grading.FAILURE_CATEGORIES.forEach(name -> failures.put(name, 0));
        Map<String, Map<String, int[]>> checkSummary = new LinkedHashMap<>();
        String status = "completed";
        String abortReason = null;
        try {
            for (String name : AgentEvalV2.GROUPS) {
                Group group = AgentEvalV2.group(name);
                Map<String, int[]> perRule = checkSummary.computeIfAbsent(name, key2 -> new TreeMap<>());
                for (JsonNode task : group.tasks()) {
                    // Offline: whether the business code under test still lets the reference solution pass.
                    boolean referencePassed = reference(group, task).passed();
                    List<Map<String, Object>> trials = new ArrayList<>();
                    int passed = 0;
                    for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                        Trial result = AgentEvalV2.run(group, task, scenario -> model);
                        Optional<String> outage = providerFailure(result);
                        if (outage.isPresent()) {
                            abortReason = outage.get() + "（" + name + "/" + task.path("id").asText() + " 第 " + repetition
                                    + " 次）；该次 trial 未计入结果";
                            throw new IllegalStateException("模型服务商故障 " + outage.get() + "，中止本次运行");
                        }
                        if (result.passed()) passed++;
                        else failures.merge(result.category(), 1, Integer::sum);
                        for (Check check : result.checks()) {
                            int[] counts = perRule.computeIfAbsent(check.name(), key2 -> new int[2]);
                            if (check.passed()) counts[0]++;
                            counts[1]++;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("repetition", repetition);
                        row.put("passed", result.passed());
                        row.put("category", result.passed() ? null : result.category());
                        row.put("checks", result.checks());
                        row.put("turnStates", result.states());
                        row.put("errorCodes", result.errorCodes());
                        row.put("replayTrace", result.trace().stream().filter(line -> line.contains("未录制条件")).toList());
                        if (!result.passed()) row.put("transcriptSummary", AgentEvalV2.summarize(result));
                        trials.add(row);
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("group", name);
                    entry.put("taskId", task.path("id").asText());
                    entry.put("rules", stream(task.path("checks")).map(AgentEvalV2Grading::rule).toList());
                    entry.put("referencePassed", referencePassed);
                    entry.put("passed", passed);
                    entry.put("total", REPETITIONS);
                    entry.put("trials", trials);
                    results.add(entry);
                }
            }
        } catch (Exception ex) {
            status = "interrupted";
            throw ex;
        } finally {
            String fileName = "results/" + time.toString().replace(":", "-") + "-" + slot + ".json";
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("evalVersion", "v2");
            report.put("timestampUtc", time.toString());
            report.put("commitSha", sha);
            report.put("targetBusinessSha", "baseline".equals(slot) ? BASELINE : sha);
            report.put("modelId", modelId);
            report.put("slot", slot);
            report.put("status", status);
            if (abortReason != null) report.put("abortReason", abortReason);
            report.put("acceptanceDigest", ACCEPTANCE_DIGEST);
            report.put("repetitionsPerTask", REPETITIONS);
            report.put("failureCategories", failures);
            Map<String, Object> summary = new LinkedHashMap<>();
            checkSummary.forEach((group, rules) -> {
                Map<String, Object> view = new LinkedHashMap<>();
                rules.forEach((rule, counts) -> view.put(rule, Map.of("passed", counts[0], "total", counts[1])));
                summary.put(group, view);
            });
            report.put("checkSummary", summary);
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
            System.out.println("Agent eval v2 report: " + output.toAbsolutePath());
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Outages of the model provider say nothing about the agent, so one of them stops the run (status interrupted,
     * the slot stays free) instead of being scored. Timeouts and malformed replies are still scored as OTHER.
     */
    static final Set<String> PROVIDER_FAILURES = Set.of("AI_BALANCE_INSUFFICIENT", "AI_AUTH_FAILED", "AI_NOT_CONFIGURED",
            "AI_RATE_LIMITED", "AI_SERVICE_UNAVAILABLE", "AI_REQUEST_ERROR", "AI_REQUEST_INTERRUPTED");

    static Optional<String> providerFailure(Trial trial) {
        Stream<String> fromTrace = trial.trace().stream().filter(line -> line.startsWith("evaluation exception: "))
                .map(line -> line.substring("evaluation exception: ".length()).split(":", 2)[0]);
        return Stream.concat(trial.errorCodes().stream(), fromTrace).filter(PROVIDER_FAILURES::contains).findFirst();
    }

    static void requireRunnable(String slot, String runLog) {
        if (!SLOTS.contains(slot)) throw new IllegalArgumentException("AGENT_EVAL_SLOT must be baseline or structured");
        if (runLog.contains("| " + slot + " | completed |")) {
            throw new IllegalStateException("v2 slot " + slot + " has already been used; create v3");
        }
    }

    /** The baseline slot must measure exactly the pre-registered business code; eval-only commits on top are allowed. */
    private static void requireBaselineBusinessCode() throws Exception {
        String business = "jd-rag-resume-backend/src/main";
        String changed = git("diff", "--name-only", BASELINE, "--", business).strip();
        String untracked = git("ls-files", "--others", "--exclude-standard", "--", business).strip();
        if (!changed.isEmpty() || !untracked.isEmpty()) {
            throw new IllegalStateException("baseline 槽位要求业务代码与 " + BASELINE + " 一致，以下文件不同：\n" + changed + "\n" + untracked);
        }
    }

    private static String git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(ROOT.resolve("../..").toFile()).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("git " + String.join(" ", args) + " failed");
        return output;
    }

    private static String require(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException(key + " is required");
        return value;
    }

    private static Trial reference(Group group, JsonNode task) throws Exception {
        return AgentEvalV2.run(group, task, scenario -> new AgentEvalV2.ReferenceAgentModel(task.path("reference"), scenario));
    }

    private interface Mutation {
        void apply(List<ArrayNode> turns) throws Exception;
    }

    private static void assertViolation(Group group, String taskId, String rule, Mutation mutation) throws Exception {
        ObjectNode task = (ObjectNode) group.task(taskId).deepCopy();
        List<ArrayNode> turns = new ArrayList<>();
        task.path("reference").forEach(turn -> turns.add((ArrayNode) turn));
        mutation.apply(turns);
        Trial trial = reference(group, task);
        Check check = trial.checks().stream().filter(item -> item.name().equals(rule)).findFirst().orElseThrow();
        assertFalse(check.passed(), taskId + " mutation should fail " + rule + "\n"
                + String.join("\n", AgentEvalV2.summarize(trial)));
        assertFalse(trial.passed());
    }

    private static JsonNode step(String tool, String args) throws Exception {
        return JSON.createObjectNode().put("tool", tool).set("args", JSON.readTree(args));
    }

    private static JsonNode last(ArrayNode steps) {
        return steps.get(steps.size() - 1);
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items.stream();
    }
}
