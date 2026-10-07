package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for the LLM when {@code ai.mock-enabled=true}: a fixed plan instead of reasoning
 * (list resumes, rank jobs, fetch evidence, answer; and start an analysis when the question
 * asks for one). It drives the real loop, real tools and the real confirmation gate, so
 * the whole pipeline can be demonstrated offline. Its answers say so explicitly.
 */
public class ScriptedAgentModel implements AgentModel {
    private static final String BANNER = "【演示模式 · 脚本模型，未调用真实大模型】\n";

    private final ObjectMapper objectMapper;
    private final AtomicLong callIds = new AtomicLong();

    public ScriptedAgentModel(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Reply next(String systemPrompt, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout) {
        int lastUser = lastUserIndex(transcript);
        String question = transcript.get(lastUser).content();
        Map<String, JsonNode> results = toolResultsSince(transcript, lastUser);
        boolean wantsAnalysis = question.contains("分析");

        JsonNode resumes = results.get("list_resumes");
        if (resumes == null) {
            return call("list_resumes", "{}");
        }
        if (failed(resumes)) {
            return Reply.answer(BANNER + "读取简历失败：" + errorMessage(resumes));
        }
        JsonNode firstResume = resumes.path("untrusted_data").path("resumes").path(0);
        if (firstResume.isMissingNode()) {
            return Reply.answer(BANNER + "你还没有上传简历，先到「我的简历」上传一份，我再帮你匹配职位。");
        }
        long resumeId = firstResume.path("resumeId").asLong();

        JsonNode ranking = results.get("rank_jobs_for_resume");
        if (ranking == null) {
            return call("rank_jobs_for_resume", "{\"resumeId\":" + resumeId + ",\"limit\":3}");
        }
        if (failed(ranking)) {
            return Reply.answer(BANNER + "职位排序失败：" + errorMessage(ranking));
        }
        JsonNode matches = ranking.path("untrusted_data").path("matches");
        if (matches.isEmpty()) {
            return Reply.answer(BANNER + "职位库还是空的，先导入几个职位再来问我。");
        }
        long topJobId = matches.path(0).path("jobId").asLong();

        JsonNode evidence = results.get("search_resume_evidence");
        if (evidence == null) {
            return call("search_resume_evidence", "{\"resumeId\":" + resumeId + ",\"jobId\":" + topJobId + "}");
        }

        JsonNode started = results.get("start_analysis");
        if (wantsAnalysis && started == null) {
            return call("start_analysis", "{\"resumeId\":" + resumeId + ",\"jobId\":" + topJobId + "}");
        }
        return Reply.answer(BANNER + summary(firstResume, matches, evidence, started));
    }

    private String summary(JsonNode resume, JsonNode matches, JsonNode evidence, JsonNode started) {
        StringBuilder text = new StringBuilder();
        text.append("按整篇语义相似度，与《").append(resume.path("title").asText()).append("》最接近的职位：\n");
        int rank = 1;
        for (JsonNode match : matches) {
            text.append(rank++).append(". ").append(match.path("title").asText())
                    .append("（").append(match.path("companyName").asText("")).append("），similarity ")
                    .append(match.path("similarity").asText()).append('\n');
        }
        if (failed(evidence)) {
            text.append("检索证据失败：").append(errorMessage(evidence)).append('\n');
        } else {
            JsonNode data = evidence.path("untrusted_data");
            text.append("对第 1 名检索到 ").append(data.path("kept").size())
                    .append(" 段过阈值（").append(data.path("threshold").asText()).append("）的简历证据，")
                    .append(data.path("filteredCount").asInt()).append(" 段被过滤。\n");
        }
        if (started != null) {
            if ("USER_REJECTED".equals(started.path("error").path("code").asText())) {
                text.append("你拒绝了这次操作，没有发起完整分析。");
            } else if (failed(started)) {
                text.append("完整分析未发起：").append(errorMessage(started));
            } else {
                text.append("已发起完整分析，进度和报告链接见上方卡片。");
            }
        } else {
            text.append("需要完整的匹配报告的话，对我说“帮我分析第一个职位”。");
        }
        return text.toString();
    }

    private Reply call(String tool, String arguments) {
        return Reply.call(AgentMessage.ToolCall.of("scripted_" + callIds.incrementAndGet(), tool, arguments));
    }

    private Map<String, JsonNode> toolResultsSince(List<AgentMessage> transcript, int from) {
        Map<String, JsonNode> results = new LinkedHashMap<>();
        for (int index = from + 1; index < transcript.size(); index++) {
            AgentMessage message = transcript.get(index);
            if (!AgentMessage.TOOL.equals(message.role())) {
                continue;
            }
            try {
                JsonNode envelope = objectMapper.readTree(message.content());
                results.put(envelope.path("tool").asText(), envelope);
            } catch (Exception ignored) {
                // Not an envelope this model produced; nothing to plan from.
            }
        }
        return results;
    }

    private static boolean failed(JsonNode envelope) {
        return !envelope.path("ok").asBoolean(false);
    }

    private static String errorMessage(JsonNode envelope) {
        return envelope.path("error").path("message").asText("未知错误");
    }

    private static int lastUserIndex(List<AgentMessage> transcript) {
        for (int index = transcript.size() - 1; index >= 0; index--) {
            if (AgentMessage.USER.equals(transcript.get(index).role())) {
                return index;
            }
        }
        throw new IllegalStateException("transcript has no user message");
    }
}
