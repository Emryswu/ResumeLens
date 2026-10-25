package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-side budget and progress accounting for a single job-search request, including browser
 * pauses. All figures are reconstructed from server-stored tool calls and results in the current
 * user turn; no model-supplied counters or browser-reported totals are trusted as instructions.
 *
 * The campaign is deliberately bounded: a soft target is not a reason to manufacture matches
 * or silently widen the user's hard constraints.
 */
final class JobSearchWorkflow {
    static final String TOOL = "collect_boss_jobs";
    static final String VERIFY_TOOL = "verify_boss_job";
    /** Every tool that loads BOSS pages must be listed here so AgentLoop checks it against the budget. */
    static final Set<String> BOSS_TOOLS = Set.of(TOOL, VERIFY_TOOL);
    static final String BUDGET_ERROR = "JOB_SEARCH_BUDGET";
    static final int MAX_SEARCHES = 3;
    static final int MAX_PAGES = 6;
    static final int MAX_VERIFICATIONS = 5;
    static final int SOFT_TARGET = 5;

    private JobSearchWorkflow() { }

    static boolean governs(String toolName) {
        return BOSS_TOOLS.contains(toolName);
    }

    static boolean applies(List<AgentMessage> transcript) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (!AgentMessage.USER.equals(transcript.get(i).role())) continue;
            String question = transcript.get(i).content();
            return question.contains("实习") || question.contains("求职") || question.contains("招聘")
                    || question.toUpperCase(Locale.ROOT).contains("BOSS")
                    || question.contains("采集") || question.contains("找工作")
                    || question.contains("找岗位") || question.contains("找职位");
        }
        return false;
    }

    static Snapshot inspect(List<AgentMessage> transcript, ObjectMapper mapper) {
        return inspect(transcript, mapper, null);
    }

    /**
     * Omit the currently proposed tool call when checking whether it can be issued. AgentLoop
     * has already appended the assistant's tool_call message at that point.
     */
    static Snapshot inspect(List<AgentMessage> transcript, ObjectMapper mapper, String excludeCallId) {
        int start = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (AgentMessage.USER.equals(transcript.get(i).role())) {
                start = i + 1;
                break;
            }
        }
        Map<String, Attempt> pending = new LinkedHashMap<>();
        Map<String, Long> pendingVerifications = new LinkedHashMap<>();
        List<Attempt> attempts = new ArrayList<>();
        Map<String, Long> attempted = new LinkedHashMap<>();
        Set<Long> verified = new LinkedHashSet<>();
        Set<Long> jobIds = new LinkedHashSet<>();
        Integer dailyPagesLeft = null;
        int pagesRead = 0;
        for (int i = start; i < transcript.size(); i++) {
            AgentMessage message = transcript.get(i);
            if (message.hasToolCalls()) {
                for (AgentMessage.ToolCall call : message.toolCalls()) {
                    if (call.id().equals(excludeCallId)) continue;
                    if (VERIFY_TOOL.equals(call.name())) {
                        long jobId = parse(mapper, call.arguments()).path("jobId").asLong(-1);
                        attempted.put(call.id(), jobId);
                        pendingVerifications.put(call.id(), jobId);
                        // Every verification takes one page from the extension's daily budget when it runs.
                        if (dailyPagesLeft != null) dailyPagesLeft = Math.max(0, dailyPagesLeft - 1);
                        continue;
                    }
                    if (!TOOL.equals(call.name())) continue;
                    JsonNode args = parse(mapper, call.arguments());
                    Attempt attempt = new Attempt(call.id(), args.path("keyword").asText("").trim(),
                            args.path("city").asText(""), args.path("experience").asText("不限"),
                            Math.max(1, args.path("maxPages").asInt(1)));
                    attempts.add(attempt);
                    pending.put(call.id(), attempt);
                }
            }
            if (!AgentMessage.TOOL.equals(message.role())) continue;
            Long verifiedCandidate = pendingVerifications.remove(message.toolCallId());
            if (verifiedCandidate != null) {
                JsonNode result = parse(mapper, message.content());
                if (refusedByBudget(result)) {
                    attempted.remove(message.toolCallId());
                    if (dailyPagesLeft != null) dailyPagesLeft++;
                } else if (result.path("ok").asBoolean(false)) {
                    JsonNode data = result.path("untrusted_data");
                    String status = data.path("verificationStatus").asText();
                    if (data.path("jobId").asLong(-1) == verifiedCandidate
                            && ("SAVED_FULL".equals(status) || "ALREADY_FULL".equals(status))
                            && "FULL".equals(data.path("captureDepth").asText())) {
                        verified.add(verifiedCandidate);
                        if ("ALREADY_FULL".equals(status)) {
                            attempted.remove(message.toolCallId());
                            if (dailyPagesLeft != null) dailyPagesLeft++;
                        }
                    }
                }
                continue;
            }
            Attempt attempt = pending.remove(message.toolCallId());
            if (attempt == null) continue;
            JsonNode result = parse(mapper, message.content());
            // A call this workflow refused never reached the browser and must not use up the budget.
            if (refusedByBudget(result)) {
                attempts.remove(attempt);
                continue;
            }
            if (!result.path("ok").asBoolean(false)) continue;
            JsonNode data = result.path("untrusted_data");
            pagesRead += Math.max(0, data.path("pagesRead").asInt(0));
            if (data.path("dailyPagesLeft").isIntegralNumber()) {
                dailyPagesLeft = data.path("dailyPagesLeft").asInt();
            }
            JsonNode ids = data.path("jobIds");
            if (ids.isArray()) {
                for (JsonNode id : ids) if (id.canConvertToLong()) jobIds.add(id.asLong());
            }
        }
        int requestedPages = attempts.stream().mapToInt(Attempt::pages).sum();
        String question = transcript.stream().filter(message -> AgentMessage.USER.equals(message.role()))
                .reduce((previous, current) -> current).map(AgentMessage::content).orElse("");
        boolean internship = !question.contains("社招") && !question.contains("全职")
                && (question.contains("实习") || question.contains("在校") || question.contains("暑期") || question.contains("寒假"));
        return new Snapshot(List.copyOf(attempts), requestedPages, pagesRead, jobIds.size(), dailyPagesLeft,
                List.copyOf(new LinkedHashSet<>(attempted.values())), List.copyOf(verified), internship);
    }

    private static boolean refusedByBudget(JsonNode result) {
        return BUDGET_ERROR.equals(result.path("error").path("code").asText());
    }

    /** The budget check for any tool in {@link #BOSS_TOOLS}; null means the call may run. */
    static String refuse(Snapshot snapshot, String toolName, JsonNode arguments) {
        if (!VERIFY_TOOL.equals(toolName)) return refuse(snapshot, arguments);
        if (snapshot.attemptedIds().size() >= MAX_VERIFICATIONS) {
            return "本次找岗位已尝试核实 " + MAX_VERIFICATIONS + " 次，达到核实上限；请根据已核实结果收尾，其余标为待核实";
        }
        if (snapshot.dailyPagesLeft() != null && snapshot.dailyPagesLeft() < 1) {
            return "BOSS 插件今日剩余页数已用完，不能再核实完整 JD；请按已有结果收尾";
        }
        long jobId = arguments.path("jobId").asLong(-1);
        if (snapshot.attemptedIds().contains(jobId) || snapshot.verifiedIds().contains(jobId)) {
            return "这个岗位本次已尝试或完成核实，不要重复打开详情页；仅已核实的岗位可用 get_job 读取完整 JD";
        }
        return null;
    }

    static String refuse(Snapshot snapshot, JsonNode arguments) {
        if (snapshot.attempts().size() >= MAX_SEARCHES) {
            return "本次找岗位已达到 " + MAX_SEARCHES + " 次采集上限，请根据现有结果收尾并说明未覆盖的方向";
        }
        int requested = Math.max(1, arguments.path("maxPages").asInt(1));
        if (snapshot.requestedPages() + requested > MAX_PAGES) {
            return "本次找岗位最多请求 " + MAX_PAGES + " 页，剩余 "
                    + Math.max(0, MAX_PAGES - snapshot.requestedPages()) + " 页；请减少 maxPages 或根据现有结果收尾";
        }
        if (snapshot.dailyPagesLeft() != null && snapshot.dailyPagesLeft() < requested) {
            return "BOSS 插件剩余每日页数只有 " + snapshot.dailyPagesLeft()
                    + " 页，请减少 maxPages 或按已获得的结果收尾";
        }
        String keyword = arguments.path("keyword").asText("").trim();
        String city = arguments.path("city").asText("");
        String experience = arguments.path("experience").asText("不限");
        boolean repeated = snapshot.attempts().stream().anyMatch(previous ->
                previous.keyword().equalsIgnoreCase(keyword) && previous.city().equals(city)
                        && previous.experience().equals(experience));
        return repeated ? "本次已经按相同城市、关键词、经验条件采集过；补搜须改变关键词或明确说明筛选调整" : null;
    }

    /** Injected on each model call, not only at the start: browser-result continuations get fresh progress. */
    static String guidance(Snapshot progress) {
        if (progress.attempts().isEmpty() && progress.attemptedIds().isEmpty() && progress.verifiedIds().isEmpty()) {
            return "\n求职搜索策略：先根据所选简历识别有证据支持的方向，优先精确关键词。"
                    + "目标约 " + SOFT_TARGET + " 个值得核实的候选岗位（软目标，非完成承诺）。"
                    + "先在本地库查看范围；本地 0 条时可去 BOSS 采集，不能推断市场没有岗位。"
                    + (progress.internship()
                        ? "用户明确要求实习或在校岗位：不要只把实习塞进 keyword，采集时用 experience=在校生；在校生并不保证是实习。"
                        : "采集时用 experience=不限，并在回答里说明这次没限制经验。")
                    + "城市用 city。采集到的是列表摘要，完整 JD 和硬性条件仍需另行核实。"
                    + "每次优先只采集 1 页，先评估数量和相关性再决定下一方向。";
        }
        StringBuilder info = new StringBuilder("\n本次求职搜索（服务端记录）：已发起 ")
                .append(progress.attempts().size()).append('/').append(MAX_SEARCHES)
                .append(" 次采集，申请 ").append(progress.requestedPages()).append('/').append(MAX_PAGES)
                .append(" 页，已读取 ").append(progress.pagesRead()).append(" 页，")
                .append("去重后候选岗位 ").append(progress.uniqueJobs()).append(" 条（未经完整 JD 核实）。");
        info.append(" 已尝试核实 ").append(progress.attemptedIds().size()).append('/').append(MAX_VERIFICATIONS)
                .append(" 次，已核实完整 JD ").append(progress.verifiedIds().size()).append(" 个。");
        if (progress.dailyPagesLeft() != null) info.append(" 插件今日剩余 ").append(progress.dailyPagesLeft()).append(" 页。");
        info.append(" 已搜条件：");
        for (Attempt attempt : progress.attempts()) {
            info.append('[').append(attempt.city()).append('，').append(attempt.keyword())
                    .append('，').append(attempt.experience()).append("] ");
        }
        boolean canContinue = progress.attempts().size() < MAX_SEARCHES
                && progress.requestedPages() < MAX_PAGES
                && (progress.dailyPagesLeft() == null || progress.dailyPagesLeft() > 0);
        if (canContinue) {
            info.append("若本批 0 条、数量不足或明显不对口，应在预算内换同方向的另一关键词，")
                    .append("或根据简历技能尝试相邻方向再补搜；勿重复相同条件，勿擅自放宽城市、学历、实习时长等硬条件。")
                    .append("先评估结果与重复率，补搜优先 maxPages=1。");
        } else {
            info.append("本次采集预算已用完，停止采集，按现有结果交差。");
        }
        info.append(" 最终说明覆盖的方向、实际候选数量、未核实要求、预算或平台限制。")
                .append("候选不足软目标也如实说明，不得凑数或称已核实合格。");
        return info.toString();
    }

    private static JsonNode parse(ObjectMapper mapper, String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return node == null ? mapper.createObjectNode() : node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Old compacted tool envelopes can be truncated; only intact envelopes provide counts.
            return mapper.createObjectNode();
        }
    }

    record Attempt(String callId, String keyword, String city, String experience, int pages) { }
    record Snapshot(List<Attempt> attempts, int requestedPages, int pagesRead, int uniqueJobs,
                    Integer dailyPagesLeft, List<Long> attemptedIds, List<Long> verifiedIds, boolean internship) { }
}
