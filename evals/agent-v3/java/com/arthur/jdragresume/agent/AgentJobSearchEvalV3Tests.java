package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.AgentEvalV3.Group;
import com.arthur.jdragresume.agent.AgentEvalV3.Trial;
import com.arthur.jdragresume.agent.AgentEvalV3Grading.Check;
import com.arthur.jdragresume.agent.AgentEvalV3Grading.Invocation;
import com.arthur.jdragresume.agent.AgentEvalV3Grading.Library;
import com.arthur.jdragresume.agent.AgentEvalV3Grading.Metrics;
import com.arthur.jdragresume.agent.tool.CheckJobPreferencesTool;
import com.arthur.jdragresume.agent.tool.CollectBossJobsTool;
import com.arthur.jdragresume.agent.tool.GetJobTool;
import com.arthur.jdragresume.agent.tool.RankJobsForResumeTool;
import com.arthur.jdragresume.agent.tool.RelaxJobSearchConditionTool;
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

import static com.arthur.jdragresume.agent.AgentEvalV3.JSON;
import static com.arthur.jdragresume.agent.AgentEvalV3.ROOT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Agent job-search evaluation v3. CI runs only the offline checks below; the real model runs only
 * through {@link #realModelRunsOnlyWhenExplicitlyRequested} with RUN_AGENT_EVAL=true (see evals/agent-v3/PROTOCOL.md).
 */
class AgentJobSearchEvalV3Tests {
    /** Business code under test for the single pre-registered slot: main when v3 was written (after #123). */
    static final String TARGET_BUSINESS = "31413632511d1805205e51d011dfe0e582122da9";
    /** Frozen acceptance inputs. Changing any acceptance task or snapshot changes this value; see PROTOCOL.md. */
    static final String ACCEPTANCE_DIGEST = "982eedc19accdc7104748d2510918ff6e8ade882b16e507892860e1c227dbac0";
    static final List<String> SLOTS = List.of("current");
    private static final int REPETITIONS = 3;
    private static final Set<String> CHECK_TYPES = Set.of("prerequisites", "inherit_filters", "stop_on_signal",
            "no_listing_as_full", "submit_accepted", "replan_offtarget", "gate_excluded", "preferred_accepted",
            "resume_evidence_on_point", "conflict_skipped", "stale_reverified");

    @Test
    void tasksAreWellFormedDisjointAndCoverEveryRule() throws Exception {
        Map<String, String> owners = new HashMap<>();
        Set<String> taskIds = new HashSet<>();
        for (String name : AgentEvalV3.GROUPS) {
            Group group = AgentEvalV3.group(name);
            assertFalse(group.tasks().isEmpty(), name);
            Map<String, JsonNode> rows = new HashMap<>();
            try (Stream<Path> files = Files.list(ROOT.resolve("snapshots/" + name + "/listings"))) {
                for (Path file : files.toList()) {
                    JsonNode task = JSON.readTree(file.toFile()).path("task");
                    for (JsonNode row : task.path("jobs")) {
                        String id = row.path("sourceJobId").asText();
                        assertNull(owners.put(id, name + "/" + file.getFileName()), "sourceJobId reused: " + id);
                        rows.put(id, row);
                        boolean halts = "NO_MORE_RESULTS".equals(task.path("finishReason").asText());
                        if (!halts && row.path("evalSimilarity").asDouble() >= AgentEvalV3Grading.ON_TARGET) {
                            assertTrue(Files.exists(ROOT.resolve("snapshots/" + name + "/details/" + id + ".json")),
                                    "on-target listing row without a detail snapshot: " + id);
                        }
                    }
                }
            }
            Set<String> library = new HashSet<>();
            try (Stream<Path> files = Files.list(ROOT.resolve("snapshots/" + name + "/library"))) {
                for (Path file : files.toList()) {
                    String id = JSON.readTree(file.toFile()).path("sourceJobId").asText();
                    assertEquals(id + ".json", file.getFileName().toString());
                    assertNull(owners.put(id, name + "/library"), "sourceJobId reused: " + id);
                    library.add(id);
                }
            }
            assertTrue(AgentEvalV3.read("snapshots/" + name + "/listings/" + AgentEvalV3.GENERIC_LISTING + ".json")
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
                    AgentEvalV3.read(file).path("task").path("jobs").forEach(row -> listed.add(row.path("sourceJobId").asText()));
                    for (JsonNode set : search.path("keywordSets")) {
                        assertTrue(group.keywordSets().has(set.asText()), id + ": unknown keyword set " + set);
                    }
                }
                Set<String> preloaded = new HashSet<>();
                for (JsonNode job : task.path("preload")) {
                    assertTrue(library.contains(job.asText()), id + ": preload without a library snapshot " + job);
                    preloaded.add(job.asText());
                }
                for (JsonNode check : task.path("checks")) {
                    String type = check.path("type").asText();
                    assertTrue(CHECK_TYPES.contains(type), id + ": unknown check " + type);
                    String job = check.path("job").asText();
                    switch (type) {
                        case "inherit_filters" -> assertTrue(check.hasNonNull("city") && check.hasNonNull("experience"), id);
                        case "stop_on_signal" -> assertTrue(Set.of("BOSS_NOT_LOGGED_IN", "NO_MORE_RESULTS")
                                .contains(check.path("signal").asText()), id);
                        case "gate_excluded", "preferred_accepted" -> {
                            assertTrue(Set.of("education", "experience").contains(check.path("rule").asText()), id);
                            assertOnTarget(id, job, listed, rows);
                        }
                        case "conflict_skipped" -> {
                            assertTrue(Set.of("listing_tag", "city").contains(check.path("rule").asText()), id);
                            // Ranked on top by similarity, so only the conflict screening can keep it from a slot.
                            assertOnTarget(id, job, listed, rows);
                        }
                        case "resume_evidence_on_point" -> {
                            assertTrue(check.path("keywords").isArray() && !check.path("keywords").isEmpty(), id);
                            assertOnTarget(id, job, listed, rows);
                        }
                        case "stale_reverified" -> {
                            assertTrue(Set.of("accept", "exclude").contains(check.path("expect").asText()), id);
                            assertTrue(preloaded.contains(job), id + ": stale job must be preloaded: " + job);
                            assertTrue(Files.exists(ROOT.resolve("snapshots/" + name + "/details/" + job + ".json")),
                                    id + ": a stale job needs its current detail snapshot");
                            assertTrue(AgentEvalV3.read("snapshots/" + name + "/library/" + job + ".json")
                                    .path("verifiedDaysAgo").asInt() > new AgentProperties().getVerifiedJdMaxAgeDays(),
                                    id + ": the preloaded verification must already be expired");
                        }
                        default -> { }
                    }
                    covered.add(AgentEvalV3Grading.rule(check));
                }
            }
            assertTrue(covered.containsAll(AgentEvalV3Grading.RULES), name + " misses rules: "
                    + AgentEvalV3Grading.RULES.stream().filter(rule -> !covered.contains(rule)).toList());
        }
    }

    private static void assertOnTarget(String task, String job, Set<String> listed, Map<String, JsonNode> rows) {
        assertTrue(listed.contains(job), task + ": checked job is not in the task's listings: " + job);
        assertTrue(rows.get(job).path("evalSimilarity").asDouble() >= AgentEvalV3Grading.ON_TARGET,
                task + ": the checked job must rank on target so a careful agent considers it");
    }

    @Test
    void acceptanceInputsAreFrozen() throws Exception {
        assertEquals(ACCEPTANCE_DIGEST, AgentEvalV3.acceptanceDigest(),
                "acceptance 任务或快照被修改；v3 冻结后不得改动，需要改变时建立 v4（见 PROTOCOL.md）");
    }

    @Test
    void referenceSolutionsPassEveryDevTaskThroughTheRealLoop() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        for (JsonNode task : dev.tasks()) {
            Trial trial = reference(dev, task);
            assertEquals("PASS", trial.category(), task.path("id").asText() + " " + trial.checks()
                    + "\n" + String.join("\n", AgentEvalV3.summarize(trial)));
        }
    }

    /**
     * Acceptance outcomes depend on the business code under test, so CI only checks that the harness replays
     * every acceptance reference without errors; whether they pass is recorded per formal run (referencePassed).
     */
    @Test
    void referenceSolutionsReplayEveryAcceptanceTask() throws Exception {
        Group acceptance = AgentEvalV3.group("acceptance");
        for (JsonNode task : acceptance.tasks()) {
            Trial trial = reference(acceptance, task);
            assertEquals(task.path("checks").size(), trial.checks().size());
            assertTrue(trial.trace().stream().noneMatch(line -> line.startsWith("evaluation exception:")),
                    task.path("id").asText() + " 回放参考解时评测框架抛出异常");
            if (stream(task.path("checks")).anyMatch(check -> "stop_on_signal".equals(check.path("type").asText()))) {
                assertTrue(trial.trace().contains("browser_task_required"), task.path("id").asText() + " 停止任务必须真正执行一次快照浏览器回放");
            }
        }
    }

    /**
     * Task premises that come from the production code: in dev, the conflict rows are screened as hard conflicts
     * and kept out of suggestedVerify, and the preloaded jobs show as expired, all through the real ranking tool.
     */
    @Test
    void devPremisesHoldThroughTheProductionRanking() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        for (JsonNode task : dev.tasks()) {
            List<JsonNode> conflicts = stream(task.path("checks")).filter(c -> "conflict_skipped".equals(c.path("type").asText())).toList();
            List<JsonNode> stale = stream(task.path("checks")).filter(c -> "stale_reverified".equals(c.path("type").asText())).toList();
            if (conflicts.isEmpty() && stale.isEmpty()) continue;
            AgentEvalV3.Scenario[] world = new AgentEvalV3.Scenario[1];
            Trial trial = AgentEvalV3.run(dev, task, scenario -> {
                world[0] = scenario;
                return new AgentEvalV3.ReferenceAgentModel(task.path("reference"), scenario);
            });
            JsonNode rank = AgentEvalV3Grading.invocations(trial.transcript()).stream()
                    .filter(call -> call.name().equals("rank_jobs_for_resume")).findFirst().orElseThrow().result()
                    .path("untrusted_data");
            for (JsonNode check : conflicts) {
                long id = world[0].ids.get(check.path("job").asText());
                JsonNode item = stream(rank.path("matches")).filter(m -> m.path("jobId").asLong() == id).findFirst().orElseThrow();
                assertTrue(item.hasNonNull("hardConflict"), check + " is not screened as a hard conflict: " + item);
                assertTrue(stream(rank.path("suggestedVerify")).noneMatch(value -> value.asLong() == id), check.toString());
            }
            for (JsonNode check : stale) {
                long id = world[0].ids.get(check.path("job").asText());
                JsonNode item = stream(rank.path("matches")).filter(m -> m.path("jobId").asLong() == id).findFirst().orElseThrow();
                assertEquals("EXPIRED", item.path("jdStatus").asText(), check.toString());
            }
        }
    }

    /**
     * The scripted demo model only knows a few resume directions and may answer without searching, so it
     * proves the pipeline runs without harness errors; the reference solutions prove each replay path.
     */
    @Test
    void scriptedModelChecksTheEvaluationPipeline() throws Exception {
        for (String name : AgentEvalV3.GROUPS) {
            Group group = AgentEvalV3.group(name);
            for (JsonNode task : group.tasks()) {
                int passed = 0;
                for (int trial = 0; trial < REPETITIONS; trial++) {
                    Trial result = AgentEvalV3.run(group, task, scenario -> new ScriptedAgentModel(JSON));
                    assertEquals(task.path("checks").size(), result.checks().size());
                    assertFalse(result.transcript().isEmpty());
                    assertTrue(result.category().equals("PASS") || AgentEvalV3Grading.FAILURE_CATEGORIES.contains(result.category()));
                    assertTrue(result.trace().stream().noneMatch(line -> line.startsWith("evaluation exception:")),
                            "评测框架抛出异常: " + task.path("id").asText() + " " + result.trace());
                    if (result.passed()) passed++;
                }
                System.out.println("Agent v3 scripted eval " + name + "/" + task.path("id").asText()
                        + ": " + passed + "/" + REPETITIONS);
            }
        }
    }

    /**
     * Each mutation breaks one rule in an otherwise passing dev reference run, and that check must turn red.
     * Only violations whose outcome does not depend on the business code under test are mutated here; the
     * gate and conflict checks are covered by {@link #gateAndConflictChecksUseTheAcceptedSubmissionAndTheFullJd}.
     */
    @Test
    void checksRejectRuleViolationsRunThroughTheRealTools() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        assertViolation(dev, "v3d01-prereq-evidence", "prerequisites", turns -> turns.get(0).insert(1,
                step("collect_boss_jobs", "{\"keyword\":\"Go 后端\",\"city\":\"成都\",\"jobType\":\"实习\",\"maxPages\":1}")));
        assertViolation(dev, "v3d01-prereq-evidence", "no_listing_as_full", turns -> ((ArrayNode) last(turns.get(0))
                .path("args").path("recommendations")).add(JSON.readTree(
                "{\"jobId\":\"$job:v3d-cd-02\",\"reason\":\"方向对口\",\"evidence\":\"基于 gRPC 实现服务间调用\","
                        + "\"resumeEvidence\":[{\"quote\":\"基于 Kafka 收集 3 台服务器日志，用 gRPC 暴露查询接口\",\"supports\":\"gRPC\"}]}")));
        assertViolation(dev, "v3d01-prereq-evidence", "submit_accepted", turns -> turns.get(0).remove(turns.get(0).size() - 1));
        // A real quote from the resume that says nothing about the job's core requirement.
        assertViolation(dev, "v3d01-prereq-evidence", "resume_evidence_on_point", turns -> ((ObjectNode) last(turns.get(0))
                .path("args").path("recommendations").get(0).path("resumeEvidence").get(0))
                .put("quote", "使用 Go + Gin 开发商品与订单服务"));
        assertViolation(dev, "v3d02-inherit-two-turns", "inherit_filters",
                turns -> ((ObjectNode) turns.get(1).get(0).path("args")).put("city", "重庆"));
        assertViolation(dev, "v3d03-stop-login", "stop_on_signal:BOSS_NOT_LOGGED_IN", turns -> turns.get(0).add(
                step("collect_boss_jobs", "{\"keyword\":\"Golang\",\"maxPages\":1}")));
        assertViolation(dev, "v3d04-stop-first-page", "stop_on_signal:NO_MORE_RESULTS", turns -> turns.get(0).add(
                step("collect_boss_jobs", "{\"keyword\":\"Go 开发\",\"maxPages\":1}")));
        assertViolation(dev, "v3d05-replan-offtarget", "replan_offtarget", turns -> turns.get(0).remove(3));
        assertViolation(dev, "v3d07-education-preferred", "preferred_accepted:education", turns -> turns.get(0).remove(turns.get(0).size() - 1));
        assertViolation(dev, "v3d09-experience-preferred", "preferred_accepted:experience", turns -> turns.get(0).remove(turns.get(0).size() - 1));
        // A gate that was never inspected in full does not count as excluded.
        assertViolation(dev, "v3d06-education-required", "gate_excluded:education", turns -> turns.get(0).remove(4));
        // Recommending an expired job without reading it again is refused, so it never becomes an accepted pick.
        assertViolation(dev, "v3d11-stale-reverify", "stale_reverified:accept", turns -> turns.get(0).remove(3));
        assertViolation(dev, "v3d11-stale-reverify", "stale_reverified:exclude", turns -> turns.get(0).remove(4));
    }

    /** Trying to verify a known conflict is refused before execution: no slot, no step, and the task still passes. */
    @Test
    void refusedConflictVerificationIsCountedButCostsNoSlot() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        ObjectNode task = (ObjectNode) dev.task("v3d10-conflict-skipped").deepCopy();
        ((ArrayNode) task.path("reference").get(0)).insert(4, step("verify_boss_job", "{\"jobId\":\"$job:v3d-cd-32\"}"));
        Trial trial = reference(dev, task);
        assertEquals("PASS", trial.category(), String.join("\n", AgentEvalV3.summarize(trial)));
        assertEquals(1, trial.metrics().conflictVerifyAttempts());
        Invocation refused = AgentEvalV3Grading.invocations(trial.transcript()).stream()
                .filter(call -> call.name().equals("verify_boss_job")).findFirst().orElseThrow();
        assertFalse(refused.result().path("executed").asBoolean(true), refused.result().toString());
    }

    @Test
    void gateAndConflictChecksUseTheAcceptedSubmissionAndTheFullJd() throws Exception {
        JsonNode task = JSON.readTree("""
                {"checks":[{"type":"gate_excluded","rule":"education","job":"gate"},
                           {"type":"preferred_accepted","rule":"experience","job":"preferred"},
                           {"type":"conflict_skipped","rule":"listing_tag","job":"gate"}]}""");
        Library library = new Library(Map.of("gate", 104L, "preferred", 105L), Map.of());
        List<AgentMessage> verified = List.of(
                AgentMessage.user("找实习"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("v1", "verify_boss_job", "{\"jobId\":104}"))),
                AgentMessage.tool("v1", "{\"ok\":true,\"tool\":\"verify_boss_job\",\"untrusted_data\":{\"jobId\":104,\"captureDepth\":\"FULL\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("v2", "verify_boss_job", "{\"jobId\":105}"))),
                AgentMessage.tool("v2", "{\"ok\":true,\"tool\":\"verify_boss_job\",\"untrusted_data\":{\"jobId\":105,\"captureDepth\":\"FULL\"}}"));
        List<Check> correct = AgentEvalV3Grading.grade(task, submitted(verified, "[105]", "[104]"), List.of(), library);
        assertTrue(correct.get(0).passed(), "a gate the server rejected is excluded");
        assertTrue(correct.get(1).passed());
        assertFalse(correct.get(2).passed(), "a known conflict must not take a verification slot");
        List<Check> wrong = AgentEvalV3Grading.grade(task, submitted(verified, "[104]", "[105]"), List.of(), library);
        assertFalse(wrong.get(0).passed(), "an accepted gate job is a violation");
        assertFalse(wrong.get(1).passed(), "a rejected preferred job is a violation");
        Metrics metrics = AgentEvalV3Grading.metrics(task, submitted(verified, "[104]", "[105]"), library);
        assertEquals(2, metrics.gatedChecks());
        assertEquals(2, metrics.gatedAccepted());
        List<Check> claimed = AgentEvalV3Grading.grade(task, List.of(AgentMessage.user("找实习"),
                AgentMessage.assistant("正式推荐 105 号岗位", null)), List.of(), library);
        assertFalse(claimed.get(0).passed(), "a gate never inspected in full is not shown to be excluded");
        assertFalse(claimed.get(1).passed(), "a recommendation claimed in prose is not an accepted submission");
        assertTrue(claimed.get(2).passed(), "a conflict never verified nor accepted is skipped");
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
    void metricsCountExecutedCallsAndAcceptedRecommendations() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        JsonNode task = dev.task("v3d01-prereq-evidence");
        Trial trial = reference(dev, task);
        assertEquals(task.path("reference").get(0).size(), trial.metrics().executedToolCalls());
        assertEquals(1, trial.metrics().acceptedRecommendations());
        assertEquals(0, trial.metrics().gatedChecks());
        Trial gated = reference(dev, dev.task("v3d06-education-required"));
        assertEquals(1, gated.metrics().gatedChecks());
        assertEquals(0, gated.metrics().gatedAccepted());
    }

    @Test
    void failureCategoriesSeparatePlanProtocolErrors() throws Exception {
        List<Check> passing = List.of(new Check("submit_accepted", true, ""));
        List<Check> failing = List.of(new Check("submit_accepted", false, "x"));
        List<AgentMessage> transcript = List.of(AgentMessage.user("找成都实习"), AgentMessage.assistant("好的", null));
        var completed = List.of(AgentLoop.State.COMPLETED);
        var failed = List.of(AgentLoop.State.FAILED);
        assertEquals("PASS", AgentEvalV3Grading.category(passing, transcript, completed, List.of(), null));
        assertEquals("RULE_VIOLATION", AgentEvalV3Grading.category(failing, transcript, completed, List.of(), null));
        assertEquals("STEP_LIMIT", AgentEvalV3Grading.category(failing, transcript, List.of(AgentLoop.State.STEP_LIMIT),
                List.of("AGENT_STEP_LIMIT"), null));
        assertEquals("SNAPSHOT_MISSING", AgentEvalV3Grading.category(failing, transcript, failed, List.of(), "EVAL_SNAPSHOT_MISSING"));
        for (String code : List.of("AGENT_PLAN_INVALID", "AGENT_PLAN_MISMATCH", "AGENT_PLAN_INCOMPLETE")) {
            assertEquals("PLAN_PROTOCOL", AgentEvalV3Grading.category(failing, transcript, failed, List.of(code), null), code);
        }
        assertEquals("OTHER", AgentEvalV3Grading.category(failing, transcript, failed, List.of("AGENT_TIMEOUT"), null));
        assertEquals("OTHER", AgentEvalV3Grading.category(failing, transcript, failed, List.of(), "IllegalStateException"));
        assertEquals("RULE_VIOLATION", AgentEvalV3Grading.category(failing, transcript, List.of(AgentLoop.State.REPLAN_LIMIT),
                List.of("AGENT_REPLAN_LIMIT"), null));
        assertEquals("OTHER", AgentEvalV3Grading.category(passing, transcript,
                List.of(AgentLoop.State.FAILED, AgentLoop.State.COMPLETED), List.of("AGENT_TIMEOUT"), null));

        Group dev = AgentEvalV3.group("dev");
        Trial trial = AgentEvalV3.run(dev, dev.task("v3d01-prereq-evidence"), scenario ->
                (system, messages, tools, timeout) -> AgentModel.Reply.call(
                        AgentMessage.ToolCall.of("plan_" + messages.size(), AgentPlan.TOOL, "{}")));
        assertEquals("PLAN_PROTOCOL", trial.category(), trial.errorCodes().toString());
        assertTrue(trial.errorCodes().contains("AGENT_PLAN_INVALID"));
    }

    @Test
    void unrecordedConditionsReplayGenericListingsAndUnrecordedDetailsFailExplicitly() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        ObjectNode task = (ObjectNode) dev.task("v3d01-prereq-evidence").deepCopy();
        task.set("reference", JSON.readTree("""
                [[{"tool":"get_resume","args":{"resumeId":"$resume"}},
                  {"tool":"get_job_search_preferences","args":{}},
                  {"tool":"collect_boss_jobs","args":{"keyword":"门店导购","city":"成都","jobType":"实习","maxPages":1}},
                  {"tool":"verify_boss_job","args":{"jobId":"$job:v3d-cd-90"}}]]"""));
        Trial trial = reference(dev, task);
        assertTrue(trial.trace().stream().anyMatch(line -> line.startsWith("未录制条件: keyword=门店导购")), trial.trace().toString());
        assertEquals("SNAPSHOT_MISSING", trial.category());
    }

    /** No task asks to loosen a condition, so a confirmation card is declined and the turn goes on. */
    @Test
    void confirmationCardsAreDeclinedAndTheTurnContinues() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        ObjectNode task = (ObjectNode) dev.task("v3d02-inherit-two-turns").deepCopy();
        task.set("reference", JSON.readTree("""
                [[{"tool":"get_resume","args":{"resumeId":"$resume"}},
                  {"tool":"get_job_search_preferences","args":{}},
                  {"tool":"relax_job_search_condition","args":{"experience":"不限","jobType":"实习"}},
                  {"tool":"collect_boss_jobs","args":{"keyword":"Go 后端","city":"成都","jobType":"实习","maxPages":1}}],
                 [{"tool":"collect_boss_jobs","args":{"keyword":"微服务","maxPages":1}}]]"""));
        Trial trial = reference(dev, task);
        assertTrue(trial.trace().contains("confirmation declined: relax_job_search_condition"), trial.trace().toString());
        assertEquals(List.of(AgentLoop.State.COMPLETED, AgentLoop.State.COMPLETED), trial.states());
        assertEquals("PASS", trial.category(), "the declined relaxation keeps every search on 在校生\n"
                + String.join("\n", AgentEvalV3.summarize(trial)));
    }

    @Test
    void graderJudgesTheEffectiveCollectorCommandNotRawArguments() throws Exception {
        JsonNode task = JSON.readTree("{\"checks\":[{\"type\":\"inherit_filters\",\"city\":\"成都\",\"experience\":\"在校生\"}]}");
        List<AgentMessage> messages = List.of(
                AgentMessage.user("找成都 Go 实习"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c1", "collect_boss_jobs",
                        "{\"keyword\":\"Go\",\"city\":\"成都\",\"jobType\":\"实习\"}"))),
                AgentMessage.tool("c1", "{\"ok\":true,\"tool\":\"collect_boss_jobs\",\"untrusted_data\":{\"jobIds\":[101]}}"),
                AgentMessage.user("换成微服务再找找"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c2", "collect_boss_jobs", "{\"keyword\":\"Go\"}"))),
                AgentMessage.tool("c2", "{\"ok\":false,\"tool\":\"collect_boss_jobs\",\"error\":{\"code\":\"JOB_SEARCH_REPEAT\"}}"),
                AgentMessage.assistant(null, List.of(AgentMessage.ToolCall.of("c3", "collect_boss_jobs", "{\"keyword\":\"微服务\"}"))),
                AgentMessage.tool("c3", "{\"ok\":true,\"tool\":\"collect_boss_jobs\",\"untrusted_data\":{\"jobIds\":[102]}}"));
        var inherited = List.of(new AgentEvalV3Grading.ReplayCommand("c1", 0, "成都", "在校生", "Go"),
                new AgentEvalV3Grading.ReplayCommand("c3", 1, "成都", "在校生", "微服务"));
        assertTrue(AgentEvalV3Grading.grade(task, messages, inherited, Library.empty()).getFirst().passed());
        var widened = List.of(inherited.getFirst(), new AgentEvalV3Grading.ReplayCommand("c3", 1, "成都", "不限", "微服务"));
        assertFalse(AgentEvalV3Grading.grade(task, messages, widened, Library.empty()).getFirst().passed());
        assertFalse(AgentEvalV3Grading.grade(task, messages, List.of(inherited.getFirst()), Library.empty()).getFirst().passed(),
                "a later turn without any collector arrival cannot show inheritance");
    }

    @Test
    void registeredToolsAreTheProductionImplementations() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        var registry = new AgentEvalV3.Scenario(dev, dev.tasks().getFirst()).registry(mock(ConversationStore.class));
        assertEquals(CollectBossJobsTool.class, registry.find("collect_boss_jobs").orElseThrow().getClass());
        assertEquals(RankJobsForResumeTool.class, registry.find("rank_jobs_for_resume").orElseThrow().getClass());
        assertEquals(VerifyBossJobTool.class, registry.find("verify_boss_job").orElseThrow().getClass());
        assertEquals(GetJobTool.class, registry.find("get_job").orElseThrow().getClass());
        assertEquals(CheckJobPreferencesTool.class, registry.find("check_job_preferences").orElseThrow().getClass());
        assertEquals(RelaxJobSearchConditionTool.class, registry.find("relax_job_search_condition").orElseThrow().getClass());
        assertEquals(SubmitRecommendationsTool.class, registry.find("submit_recommendations").orElseThrow().getClass());
    }

    @Test
    void theSingleSlotIsPreRegisteredAndSingleUse() {
        assertThrows(IllegalArgumentException.class, () -> requireRunnable("baseline", ""));
        assertDoesNotThrow(() -> requireRunnable("current", "| 2026-11-01T00:00:00Z | abc | m | current | interrupted | x |\n"));
        assertThrows(IllegalStateException.class, () ->
                requireRunnable("current", "| 2026-11-01T00:00:00Z | abc | m | current | completed | x |\n"));
    }

    @Test
    void everyProfileFieldReachesTheBusinessPreferences() throws Exception {
        for (String name : AgentEvalV3.GROUPS) {
            var view = AgentEvalV3.preferencesView(AgentEvalV3.group(name).profile().path("preferences"));
            assertTrue(view.configured(), name);
            assertEquals(2, view.educationLevel(), name);
            assertEquals(Boolean.TRUE, view.currentlyStudent(), name);
            assertEquals(2027, view.expectedGraduationDate().getYear(), name);
            assertEquals(0, view.workExperienceYears(), name);
            assertEquals(4, view.daysPerWeek(), name);
        }
    }

    @Test
    void providerFailuresAbortTheRunInsteadOfBeingScored() throws Exception {
        Group dev = AgentEvalV3.group("dev");
        JsonNode task = dev.tasks().getFirst();
        for (String code : List.of("AI_BALANCE_INSUFFICIENT", "AI_AUTH_FAILED", "AI_RATE_LIMITED", "AI_SERVICE_UNAVAILABLE")) {
            Trial trial = AgentEvalV3.run(dev, task, scenario -> (system, transcript, tools, timeout) -> {
                throw new com.arthur.jdragresume.exception.BusinessException(code, "provider failure");
            });
            assertEquals(Optional.of(code), providerFailure(trial), trial.errorCodes() + " " + trial.trace());
        }
        Trial scored = AgentEvalV3.run(dev, task, scenario -> (system, transcript, tools, timeout) ->
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
        assertEquals(ACCEPTANCE_DIGEST, AgentEvalV3.acceptanceDigest(), "acceptance 输入与冻结版本不一致，拒绝正式运行");
        requireTargetBusinessCode();
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
        AgentEvalV3Grading.FAILURE_CATEGORIES.forEach(name -> failures.put(name, 0));
        Map<String, Map<String, int[]>> checkSummary = new LinkedHashMap<>();
        Map<String, int[]> totals = new LinkedHashMap<>();
        String status = "completed";
        String abortReason = null;
        try {
            for (String name : AgentEvalV3.GROUPS) {
                Group group = AgentEvalV3.group(name);
                Map<String, int[]> perRule = checkSummary.computeIfAbsent(name, key2 -> new TreeMap<>());
                // trials, passed, executed calls (trials with an accepted pick), accepted picks, gated, gated accepted, conflict attempts
                int[] sums = totals.computeIfAbsent(name, key2 -> new int[7]);
                for (JsonNode task : group.tasks()) {
                    boolean referencePassed = reference(group, task).passed();
                    List<Map<String, Object>> trials = new ArrayList<>();
                    int passed = 0;
                    for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                        Trial result = AgentEvalV3.run(group, task, scenario -> model);
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
                        Metrics metrics = result.metrics();
                        sums[0]++;
                        if (result.passed()) sums[1]++;
                        if (metrics.acceptedRecommendations() > 0) {
                            sums[2] += metrics.executedToolCalls();
                            sums[3] += metrics.acceptedRecommendations();
                        }
                        sums[4] += metrics.gatedChecks();
                        sums[5] += metrics.gatedAccepted();
                        sums[6] += metrics.conflictVerifyAttempts();
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("repetition", repetition);
                        row.put("passed", result.passed());
                        row.put("category", result.passed() ? null : result.category());
                        row.put("checks", result.checks());
                        row.put("metrics", metrics);
                        row.put("turnStates", result.states());
                        row.put("errorCodes", result.errorCodes());
                        row.put("replayTrace", result.trace().stream().filter(line -> line.contains("未录制条件")
                                || line.startsWith("confirmation declined")).toList());
                        if (!result.passed()) row.put("transcriptSummary", AgentEvalV3.summarize(result));
                        trials.add(row);
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("group", name);
                    entry.put("taskId", task.path("id").asText());
                    entry.put("rules", stream(task.path("checks")).map(AgentEvalV3Grading::rule).toList());
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
            report.put("evalVersion", "v3");
            report.put("timestampUtc", time.toString());
            report.put("commitSha", sha);
            report.put("targetBusinessSha", TARGET_BUSINESS);
            report.put("modelId", modelId);
            report.put("slot", slot);
            report.put("status", status);
            if (abortReason != null) report.put("abortReason", abortReason);
            report.put("acceptanceDigest", ACCEPTANCE_DIGEST);
            report.put("repetitionsPerTask", REPETITIONS);
            report.put("failureCategories", failures);
            Map<String, Object> metricSummary = new LinkedHashMap<>();
            totals.forEach((group, sums) -> {
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("taskCompletion", Map.of("passed", sums[1], "total", sums[0]));
                view.put("gateFalseRecommendation", Map.of("accepted", sums[5], "gatedChecks", sums[4]));
                view.put("toolCallsPerAcceptedRecommendation", Map.of("executedToolCalls", sums[2], "acceptedRecommendations", sums[3]));
                view.put("conflictVerifyAttempts", sums[6]);
                metricSummary.put(group, view);
            });
            report.put("metrics", metricSummary);
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
            String separator = Files.readString(log).endsWith("\n") ? "" : "\n";
            Files.writeString(log, separator + "| " + time + " | " + sha + " | " + modelId + " | " + slot + " | " + status
                    + " | " + fileName + " |\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.println("Agent eval v3 report: " + output.toAbsolutePath());
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
        if (!SLOTS.contains(slot)) throw new IllegalArgumentException("AGENT_EVAL_SLOT must be current");
        if (runLog.contains("| " + slot + " | completed |")) {
            throw new IllegalStateException("v3 slot " + slot + " has already been used; create v4");
        }
    }

    /** The slot must measure exactly the pre-registered business code; eval-only commits on top are allowed. */
    private static void requireTargetBusinessCode() throws Exception {
        String business = "jd-rag-resume-backend/src/main";
        String changed = git("diff", "--name-only", TARGET_BUSINESS, "--", business).strip();
        String untracked = git("ls-files", "--others", "--exclude-standard", "--", business).strip();
        if (!changed.isEmpty() || !untracked.isEmpty()) {
            throw new IllegalStateException("current 槽位要求业务代码与 " + TARGET_BUSINESS + " 一致，以下文件不同：\n" + changed + "\n" + untracked);
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
        return AgentEvalV3.run(group, task, scenario -> new AgentEvalV3.ReferenceAgentModel(task.path("reference"), scenario));
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
                + String.join("\n", AgentEvalV3.summarize(trial)));
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
