package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Scoring semantics of evaluation v2: the checks, failure categories and thresholds. This file is frozen
 * together with the acceptance inputs and is part of {@code ACCEPTANCE_DIGEST}; wiring repairs go to
 * {@link AgentEvalV2} only (see evals/agent-v2/PROTOCOL.md).
 */
final class AgentEvalV2Grading {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> FAILURE_CATEGORIES = List.of(
            "STEP_LIMIT", "RULE_VIOLATION", "SNAPSHOT_MISSING", "PLAN_PROTOCOL", "OTHER");
    /** Every group must cover each of these rules with at least one task. */
    static final List<String> RULES = List.of("prerequisites", "inherit_filters",
            "stop_on_signal:BOSS_NOT_LOGGED_IN", "stop_on_signal:NO_MORE_RESULTS", "no_listing_as_full",
            "submit_accepted", "replan_offtarget", "gate_excluded:education", "preferred_accepted:education",
            "gate_excluded:experience", "preferred_accepted:experience");
    /** Listing rows ranked below this are off target for the resume. */
    static final double ON_TARGET = 0.5;
    private static final Pattern KEYWORD_SUFFIX = Pattern.compile("(实习生|实习|岗位)$");

    private AgentEvalV2Grading() { }

    record Check(String name, boolean passed, String reason) { }

    record Invocation(String callId, String name, JsonNode args, JsonNode result, int index, int turn) { }

    /** Effective command the replay collector received, after inherited filters were applied; not raw model JSON. */
    record ReplayCommand(String callId, int turn, String city, String experience, String keyword) { }

    /** Runtime facts about the replayed job library the graders may use. */
    record Library(Map<String, Long> ids, Map<Long, Double> similarity) {
        static Library empty() { return new Library(Map.of(), Map.of()); }
    }

    /** The coverage label of one check, e.g. {@code gate_excluded:education}. */
    static String rule(JsonNode check) {
        String type = check.path("type").asText();
        return switch (type) {
            case "stop_on_signal" -> type + ":" + check.path("signal").asText();
            case "gate_excluded", "preferred_accepted" -> type + ":" + check.path("rule").asText();
            default -> type;
        };
    }

    /** Case and white space do not matter, and a trailing 实习/实习生/岗位 is ignored. */
    static String normalizeKeyword(String keyword) {
        String value = Objects.requireNonNullElse(keyword, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        for (String stripped = KEYWORD_SUFFIX.matcher(value).replaceAll(""); !stripped.equals(value);
             stripped = KEYWORD_SUFFIX.matcher(value).replaceAll("")) {
            value = stripped;
        }
        return value;
    }

    /**
     * A trial passes only when every turn completed and every check passed. Otherwise the first matching
     * category wins: a missing snapshot, the step budget, a plan protocol error that ended a turn
     * (code {@code AGENT_PLAN_*}), any other loop failure or harness exception, and finally rule violations.
     */
    static String category(List<Check> checks, List<AgentMessage> transcript, List<AgentLoop.State> states,
                           List<String> errors, String exception) {
        if ("EVAL_SNAPSHOT_MISSING".equals(exception) || transcript.stream().anyMatch(m -> m.content() != null
                && m.content().contains("EVAL_SNAPSHOT_MISSING"))) return "SNAPSHOT_MISSING";
        if (states.contains(AgentLoop.State.STEP_LIMIT) || errors.contains("AGENT_STEP_LIMIT")
                || transcript.stream().anyMatch(m -> m.content() != null && m.content().contains("AGENT_STEP_LIMIT")))
            return "STEP_LIMIT";
        if (Stream.concat(errors.stream(), Stream.ofNullable(exception)).anyMatch(code -> code.startsWith("AGENT_PLAN_")))
            return "PLAN_PROTOCOL";
        if (exception != null || states.stream().anyMatch(state -> state == AgentLoop.State.TIMED_OUT
                || state == AgentLoop.State.FAILED || state == AgentLoop.State.CANCELLED)) return "OTHER";
        if (checks.stream().anyMatch(check -> !check.passed())
                || states.isEmpty() || states.stream().anyMatch(state -> state != AgentLoop.State.COMPLETED))
            return "RULE_VIOLATION";
        return "PASS";
    }

    static List<Invocation> invocations(List<AgentMessage> messages) {
        Map<String, JsonNode> results = new HashMap<>();
        for (AgentMessage message : messages) if (AgentMessage.TOOL.equals(message.role())) {
            try { results.put(message.toolCallId(), JSON.readTree(message.content())); }
            catch (IOException ex) { throw new IllegalStateException("invalid tool JSON", ex); }
        }
        List<Invocation> calls = new ArrayList<>();
        int turn = -1;
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if (AgentMessage.USER.equals(message.role())) turn++;
            if (!message.hasToolCalls()) continue;
            for (AgentMessage.ToolCall call : message.toolCalls()) {
                try {
                    calls.add(new Invocation(call.id(), call.name(), JSON.readTree(call.arguments()),
                            results.get(call.id()), index, turn));
                } catch (IOException ex) {
                    throw new IllegalStateException("invalid tool arguments", ex);
                }
            }
        }
        return calls;
    }

    static List<Check> grade(JsonNode task, List<AgentMessage> transcript, List<ReplayCommand> replayed, Library library) {
        List<Invocation> calls = invocations(transcript);
        Set<Long> verified = new HashSet<>();
        for (Invocation call : calls) if (call.name().equals("verify_boss_job") && ok(call.result())
                && "FULL".equals(data(call).path("captureDepth").asText())) {
            verified.add(data(call).path("jobId").asLong());
        }
        Set<Long> accepted = new HashSet<>();
        for (Invocation call : calls) if (call.name().equals("submit_recommendations") && ok(call.result())) {
            data(call).path("recommendations").forEach(item -> accepted.add(item.path("jobId").asLong()));
        }
        List<Invocation> collects = calls.stream().filter(c -> c.name().equals("collect_boss_jobs")).toList();
        List<Check> verdicts = new ArrayList<>();
        for (JsonNode check : task.path("checks")) {
            String type = check.path("type").asText();
            boolean passed;
            String failure;
            switch (type) {
                case "prerequisites" -> {
                    Invocation first = collects.isEmpty() ? null : collects.getFirst();
                    passed = first != null && calls.stream().anyMatch(c -> c.name().equals("get_resume")
                            && c.index() < first.index() && ok(c.result()) && data(c).hasNonNull("text"))
                            && calls.stream().anyMatch(c -> c.name().equals("get_job_search_preferences")
                            && c.index() < first.index() && ok(c.result()));
                    failure = "第一次采集前没有成功读取简历正文和求职条件";
                }
                case "inherit_filters" -> {
                    String city = check.path("city").asText();
                    String experience = check.path("experience").asText();
                    // Only collector arrivals count: refused calls (JOB_SEARCH_REPEAT etc.) never searched anything.
                    passed = replayed.stream().anyMatch(command -> command.turn() == 0)
                            && replayed.stream().anyMatch(command -> command.turn() > 0)
                            && replayed.stream().allMatch(command -> city.equals(command.city())
                                    && experience.equals(command.experience()));
                    failure = "不是每次到达采集器的搜索都保持 city=" + city + "、experience=" + experience
                            + "，或缺少首轮/后续轮的实际搜索";
                }
                case "stop_on_signal" -> {
                    String signal = check.path("signal").asText();
                    Invocation stopped = collects.stream().filter(c -> stopSignal(c.result(), signal)).findFirst().orElse(null);
                    passed = stopped != null && collects.stream().noneMatch(c ->
                            c.turn() == stopped.turn() && c.index() > stopped.index());
                    failure = stopped == null ? "没有观察到 " + signal + " 停止信号" : "遇到 " + signal + " 后同一轮仍再次采集";
                }
                case "no_listing_as_full" -> {
                    passed = true;
                    for (Invocation call : calls) if (call.name().equals("submit_recommendations")) {
                        // Proposing a LISTING-only job is the violation, even when the server rejects it.
                        for (JsonNode proposal : call.args().path("recommendations")) {
                            if (!verified.contains(proposal.path("jobId").asLong())) passed = false;
                        }
                    }
                    failure = "提交了未经 verify_boss_job 核实完整 JD 的岗位（即使服务端已拒绝）";
                }
                case "submit_accepted" -> {
                    passed = !accepted.isEmpty();
                    failure = "没有经 submit_recommendations 验收通过至少一个岗位";
                }
                case "replan_offtarget" -> {
                    Invocation first = collects.stream().filter(c -> arrived(c, replayed)).findFirst().orElse(null);
                    Invocation second = first == null ? null : collects.stream()
                            .filter(c -> c.index() > first.index() && arrived(c, replayed) && ok(c.result()))
                            .findFirst().orElse(null);
                    boolean offTarget = first != null && ok(first.result()) && !data(first).path("jobIds").isEmpty();
                    if (offTarget) for (JsonNode id : data(first).path("jobIds")) {
                        if (library.similarity().getOrDefault(id.asLong(), 0.0) >= ON_TARGET) offTarget = false;
                    }
                    boolean rankedBetween = second != null && calls.stream().anyMatch(c ->
                            c.name().equals("rank_jobs_for_resume") && ok(c.result())
                                    && c.index() > first.index() && c.index() < second.index());
                    boolean newKeyword = second != null && !normalizeKeyword(command(first, replayed).keyword())
                            .equals(normalizeKeyword(command(second, replayed).keyword()));
                    boolean withinBudget = collects.stream().noneMatch(c -> c.result() != null
                            && JobSearchWorkflow.BUDGET_ERROR.equals(c.result().path("error").path("code").asText()));
                    passed = offTarget && rankedBetween && newKeyword && withinBudget;
                    failure = !offTarget ? "首轮实际采集结果不是全部不对口，任务前提不成立"
                            : "首轮不对口后没有先排序、再换关键词成功补搜（或触发了采集预算拒绝）";
                }
                case "gate_excluded" -> {
                    Long id = library.ids().get(check.path("job").asText());
                    boolean inspected = id != null && verified.contains(id);
                    passed = inspected && !accepted.contains(id);
                    failure = !inspected ? "没有核实门槛岗位的完整 JD，无法证明是依据门槛排除"
                            : "不满足" + label(check) + "硬门槛的岗位通过了正式验收";
                }
                case "preferred_accepted" -> {
                    Long id = library.ids().get(check.path("job").asText());
                    passed = id != null && verified.contains(id) && accepted.contains(id);
                    failure = label(check) + "只是「优先」的岗位没有核实后通过正式验收";
                }
                default -> throw new IllegalStateException("unknown check " + type);
            }
            verdicts.add(new Check(rule(check), passed, passed ? "" : failure));
        }
        return verdicts;
    }

    private static String label(JsonNode check) {
        return "education".equals(check.path("rule").asText()) ? "学历" : "经验";
    }

    private static boolean stopSignal(JsonNode result, String signal) {
        if (result == null) return false;
        JsonNode data = result.path("untrusted_data");
        if ("NO_MORE_RESULTS".equals(signal)) {
            return ok(result) && signal.equals(data.path("finishReason").asText()) && data.path("pagesRead").asInt() == 1;
        }
        return signal.equals(result.path("error").path("code").asText()) || signal.equals(data.path("stopReason").asText());
    }

    private static boolean arrived(Invocation call, List<ReplayCommand> replayed) {
        return replayed.stream().anyMatch(command -> command.callId().equals(call.callId()));
    }

    private static ReplayCommand command(Invocation call, List<ReplayCommand> replayed) {
        return replayed.stream().filter(command -> command.callId().equals(call.callId())).findFirst().orElseThrow();
    }

    static boolean ok(JsonNode result) {
        return result != null && result.path("ok").asBoolean();
    }

    static JsonNode data(Invocation call) {
        return call.result().path("untrusted_data");
    }
}
