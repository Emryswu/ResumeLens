package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.collect.BrowserExtensionJobCollector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Offline demonstration of the resume-first workflow. Production direction judgments belong to the LLM. */
final class ScriptedJobSearch {
    private static final String BANNER = "【演示模式 · 脚本模型，未调用真实大模型】\n";
    // Deliberately limited demo vocabulary; unknown directions ask for clarification instead of defaulting to Java.
    private static final List<Direction> DIRECTIONS = List.of(
            new Direction("Java 后端", "Java开发", Pattern.compile("(?<![a-z])java(?![a-z])|spring")),
            new Direction("前端开发", "Web前端", Pattern.compile("前端|react|vue|typescript|javascript")),
            new Direction("Python 开发", "Python工程师", Pattern.compile("python|django|fastapi")),
            new Direction("AI 应用开发", "大模型应用", Pattern.compile("ai应用|ai 应用|rag|agent|大模型")),
            new Direction("数据分析", "数据分析师", Pattern.compile("数据分析|数据挖掘")),
            new Direction("客服", "客户服务", Pattern.compile("客服|客户服务")),
            new Direction("运营", "用户运营", Pattern.compile("运营")));

    private final ObjectMapper mapper;
    private final BiFunction<String, String, AgentModel.Reply> calls;

    ScriptedJobSearch(ObjectMapper mapper, BiFunction<String, String, AgentModel.Reply> calls) {
        this.mapper = mapper;
        this.calls = calls;
    }

    static boolean handles(String question) {
        return question.toUpperCase(Locale.ROOT).contains("BOSS") || question.contains("采集") || question.contains("收集")
                || question.contains("找") && (question.contains("实习") || question.contains("岗位") || question.contains("工作") || question.contains("职位"));
    }

    static boolean answeringDirectionChoice(List<AgentMessage> transcript, int lastUser) {
        return lastUser > 0 && transcript.get(lastUser - 1).content() != null
                && transcript.get(lastUser - 1).content().endsWith("你想按哪个方向找？");
    }

    AgentModel.Reply next(String question, List<AgentMessage> transcript, int lastUser,
                         Map<String, JsonNode> results, Map<String, JsonNode> history) {
        JsonNode collected = results.get("collect_boss_jobs");
        if (failed(collected)) return answer("这次没有采集到岗位：" + error(collected));
        JsonNode read = results.get("get_resume");
        if (failed(read)) return answer("读取简历失败：" + error(read));
        Direction requested = direction(question);
        JsonNode selected = history.containsKey("get_resume") ? history.get("get_resume").path("untrusted_data") : null;
        boolean replace = read == null && (question.contains("最新") || question.contains("换简历") || question.contains("新上传"));
        if (selected == null || !selected.hasNonNull("resumeId") || replace
                || requested != null && requested != direction(selected)) {
            JsonNode listed = results.get("list_resumes");
            if (listed == null) return call("list_resumes", Map.of());
            if (failed(listed)) return answer("读取简历失败：" + error(listed));
            JsonNode page = listed.path("untrusted_data");
            if (listed.path("truncated").asBoolean()) return answer("简历列表被截断了，请先指定求职方向，避免遗漏其他方向的简历。");
            if (page.path("page").asInt() + 1 < page.path("totalPages").asInt(1)) {
                return call("list_resumes", Map.of("page", page.path("page").asInt() + 1));
            }
            List<JsonNode> candidates = candidates(transcript, lastUser);
            if (candidates.isEmpty()) return answer("你还没有上传简历，请先上传简历或告诉我想找的方向。");
            List<JsonNode> matching = requested == null ? candidates
                    : candidates.stream().filter(row -> requested == direction(row)).toList();
            if (matching.isEmpty()) return answer("没有找到明确对应你指定方向的简历。" + choices(candidates));
            if (requested == null && candidates.size() > 1
                    && (candidates.stream().map(ScriptedJobSearch::direction).distinct().count() != 1 || direction(candidates.getFirst()) == null)) {
                return answer(choices(candidates));
            }
            JsonNode chosen = matching.stream().max(Comparator.<JsonNode, String>comparing(row -> row.path("updatedAt").asText())
                    .thenComparingLong(row -> row.path("resumeId").asLong())).orElseThrow();
            return call("get_resume", Map.of("resumeId", chosen.path("resumeId").asLong()));
        }
        Direction target = direction(selected);
        if (target == null) return answer("已读《" + selected.path("title").asText() + "》，暂时无法确定方向。你想找哪类岗位？");
        String basis = requested == null ? "按你的《" + selected.path("title").asText() + "》简历推断，先找" + target.label() + "方向。"
                : "本次使用《" + selected.path("title").asText() + "》，按你指定的" + requested.label() + "方向查找。";
        if (collected == null) {
            String city = null;
            String experience = null;
            for (int i = lastUser; i >= 0; i--) {
                AgentMessage message = transcript.get(i);
                if (!AgentMessage.USER.equals(message.role())) continue;
                String text = message.content();
                if (city == null) city = BrowserExtensionJobCollector.CITIES.stream().filter(text::contains).findFirst().orElse(null);
                if (experience == null) experience = text.contains("社招") ? "不限" : text.contains("实习") ? "在校生" : text.contains("校招") ? "应届生" : null;
            }
            if (city == null) city = "全国";
            if (experience == null) experience = "不限";
            var reply = call("collect_boss_jobs", Map.of("keyword", target.label(), "city", city, "experience", experience, "maxPages", 1));
            return new AgentModel.Reply(BANNER + basis, reply.toolCalls());
        }
        JsonNode data = collected.path("untrusted_data");
        JsonNode ids = data.path("jobIds");
        if (ids.isEmpty()) {
            var progress = JobSearchWorkflow.inspect(transcript, mapper);
            if (progress.attempts().size() == 1 && progress.requestedPages() < JobSearchWorkflow.MAX_PAGES) {
                var previous = progress.attempts().getFirst();
                String alternative = target.alternative();
                if (!previous.keyword().equalsIgnoreCase(alternative)
                        && (progress.dailyPagesLeft() == null || progress.dailyPagesLeft() > 0)) {
                    return call("collect_boss_jobs", Map.of("keyword", alternative,
                            "city", previous.city(), "experience", previous.experience(), "maxPages", 1));
                }
            }
            return answer(basis + "这次没有可排序的岗位，已尝试的搜索条件："
                    + progress.attempts().stream().map(JobSearchWorkflow.Attempt::keyword).toList()
                    + "。当前范围没有找到候选，不能据此断定市场上没有实习；未获取完整 JD。");
        }
        JsonNode ranked = results.get("rank_jobs_for_resume");
        if (ranked == null) return call("rank_jobs_for_resume", Map.of("resumeId", selected.path("resumeId").asLong(), "jobIds", ids, "limit", 5));
        if (failed(ranked)) return answer("岗位已采集，但按《" + selected.path("title").asText() + "》排序失败：" + error(ranked));
        List<JsonNode> visible = new ArrayList<>();
        JsonNode matches = ranked.path("untrusted_data").path("matches");
        (data.path("jobs").isEmpty() ? matches : data.path("jobs")).forEach(visible::add);
        long unrelated = visible.stream().filter(job -> direction(job) != null && direction(job) != target).count();
        if (unrelated > visible.size() / 2) {
            return answer(basis + "已用这份简历对本批岗位排序，目前看到的岗位大多不对口，例如“"
                    + visible.getFirst().path("title").asText() + "”。可以把关键词换成“" + target.alternative() + "”重新采集。");
        }
        StringBuilder text = new StringBuilder(basis).append("已按这份简历对本次采集岗位粗排，优先查看：\n");
        List<JsonNode> preferred = new ArrayList<>();
        matches.forEach(match -> { if (direction(match) == target) preferred.add(match); });
        if (preferred.isEmpty()) matches.forEach(preferred::add);
        for (JsonNode match : preferred) text.append(match.path("title").asText()).append("（").append(match.path("companyName").asText()).append("）\n");
        text.append("这些是列表摘要，粗排相似度不是匹配分，仍需查看完整 JD 核实。");
        return answer(text.toString());
    }

    private List<JsonNode> candidates(List<AgentMessage> transcript, int lastUser) {
        Map<Long, JsonNode> rows = new LinkedHashMap<>();
        for (AgentMessage message : transcript.subList(lastUser + 1, transcript.size())) {
            if (!AgentMessage.TOOL.equals(message.role())) continue;
            JsonNode envelope;
            try { envelope = mapper.readTree(message.content()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("invalid scripted tool result", ex); }
            if ("list_resumes".equals(envelope.path("tool").asText()) && envelope.path("ok").asBoolean()) {
                envelope.path("untrusted_data").path("resumes").forEach(row -> rows.put(row.path("resumeId").asLong(), row));
            }
        }
        return List.copyOf(rows.values());
    }

    private static String choices(List<JsonNode> candidates) {
        StringBuilder text = new StringBuilder("你的简历方向有这些：\n");
        for (JsonNode row : candidates) {
            Direction direction = direction(row);
            text.append("《").append(row.path("title").asText()).append("》：")
                    .append(direction == null ? "方向待确认" : direction.label()).append('\n');
        }
        return text.append("你想按哪个方向找？").toString();
    }

    private static Direction direction(JsonNode row) {
        Direction title = direction(row.path("title").asText());
        return title != null ? title : direction(row.path("directionEvidence").asText() + " " + row.path("text").asText());
    }
    private static Direction direction(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return DIRECTIONS.stream().filter(direction -> direction.pattern().matcher(lower).find()).findFirst().orElse(null);
    }
    private AgentModel.Reply call(String name, Object arguments) { return calls.apply(name, mapper.valueToTree(arguments).toString()); }
    private static AgentModel.Reply answer(String text) { return AgentModel.Reply.answer(BANNER + text); }
    private static boolean failed(JsonNode result) { return result != null && !result.path("ok").asBoolean(); }
    private static String error(JsonNode result) { return result.path("error").path("message").asText(); }
    private record Direction(String label, String alternative, Pattern pattern) { }
}
