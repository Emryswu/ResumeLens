package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.ResumeEvidenceValidator;
import com.arthur.jdragresume.agent.tool.SubmitRecommendationsTool;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates recommendation tool decisions from the current turn transcript and renders the
 * deterministic user-facing summary.
 */
final class RecommendationSummaryFormatter {
    private final ObjectMapper objectMapper;

    RecommendationSummaryFormatter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String formatSubmitted(List<AgentMessage> transcript) {
        Map<Long, JsonNode> passed = new LinkedHashMap<>();
        Map<Long, JsonNode> pending = new LinkedHashMap<>();
        collectDecisions(transcript, passed, pending);

        StringBuilder answer = new StringBuilder();
        boolean needsProfileCompletion = appendPassedSection(answer, passed);
        if (needsProfileCompletion) {
            answer.append("\n在求职条件页填写学历/毕业时间后可完成判断");
        }
        appendPendingSection(answer, pending);
        return answer.toString();
    }

    private void collectDecisions(
            List<AgentMessage> transcript,
            Map<Long, JsonNode> passed,
            Map<Long, JsonNode> pending
    ) {
        for (AgentMessage message : currentResults(transcript)) {
            JsonNode result = readEnvelope(message.content());
            if (!SubmitRecommendationsTool.TOOL.equals(result.path("tool").asText())
                    || !result.path("ok").asBoolean(false)) {
                continue;
            }
            JsonNode data = result.path("untrusted_data");
            for (JsonNode item : data.path("recommendations")) {
                long id = item.path("jobId").asLong();
                passed.putIfAbsent(id, item);
                pending.remove(id);
            }
            for (JsonNode item : data.path("rejected")) {
                long id = item.path("jobId").asLong();
                if (!passed.containsKey(id)) {
                    pending.put(id, item);
                }
            }
        }
    }

    private static boolean appendPassedSection(StringBuilder answer, Map<Long, JsonNode> passed) {
        if (passed.isEmpty()) {
            answer.append("本轮没有通过完整 JD 与原文证据验收的正式推荐。");
            return false;
        }
        answer.append("通过验收的岗位：");
        int index = 0;
        boolean needsProfileCompletion = false;
        for (JsonNode item : passed.values()) {
            answer.append("\n").append(++index).append(". ")
                    .append(item.path("title").asText()).append("｜")
                    .append(item.path("company").asText()).append("｜")
                    .append(item.path("location").asText())
                    .append("\n推荐理由：").append(item.path("reason").asText())
                    .append("\nJD 原文证据：「").append(item.path("evidence").asText()).append("」");
            for (JsonNode citation : item.path("resumeEvidence")) {
                answer.append("\n简历原文证据：「").append(citation.path("quote").asText()).append("」")
                        .append("\n对应 JD 要求：").append(citation.path("supports").asText());
            }
            appendJoinedArray(answer, item.path("jdNotes"), "\nJD 门槛说明：");
            appendJoinedArray(answer, item.path("preferredWarnings"), "\n优先条件提示：");
            if (item.path("missingUserFields").isArray() && !item.path("missingUserFields").isEmpty()) {
                needsProfileCompletion = true;
            }
            appendJoinedArray(answer, item.path("unknownConditions"), "\n仍需确认：");
        }
        return needsProfileCompletion;
    }

    private static void appendPendingSection(StringBuilder answer, Map<Long, JsonNode> pending) {
        if (pending.isEmpty()) {
            return;
        }
        answer.append("\n待核实岗位：");
        for (JsonNode item : pending.values()) {
            answer.append("\n").append(item.path("title").asText("未确认岗位"))
                    .append("：").append(item.path("reason").asText("尚未通过验收")
                            .replaceAll("（jobId=\\d+）", ""));
        }
    }

    private static void appendJoinedArray(StringBuilder answer, JsonNode items, String heading) {
        if (!items.isArray() || items.isEmpty()) {
            return;
        }
        answer.append(heading);
        for (int j = 0; j < items.size(); j++) {
            if (j > 0) {
                answer.append("、");
            }
            answer.append(items.get(j).asText());
        }
    }

    int recommendationAttempts(List<AgentMessage> transcript) {
        int count = 0;
        for (AgentMessage message : currentResults(transcript)) {
            JsonNode result = readEnvelope(message.content());
            if (SubmitRecommendationsTool.TOOL.equals(result.path("tool").asText())
                    && ToolEnvelope.executed(result)) {
                count++;
            }
        }
        return count;
    }

    boolean resumeEvidenceRejected(List<AgentMessage> transcript) {
        for (AgentMessage message : currentResults(transcript).reversed()) {
            JsonNode result = readEnvelope(message.content());
            if (!SubmitRecommendationsTool.TOOL.equals(result.path("tool").asText())) {
                continue;
            }
            if (ResumeEvidenceValidator.CODE.equals(result.path("error").path("code").asText())) {
                return true;
            }
            for (JsonNode item : result.path("untrusted_data").path("rejected")) {
                if (item.path("notExecuted").asBoolean(false)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    String recommendationSafeAnswer(String answer, List<AgentMessage> transcript) {
        String content = sanitizeAnswer(answer);
        boolean searchExecuted = false;
        boolean anyApproved = false;
        for (AgentMessage message : currentResults(transcript)) {
            JsonNode result = readEnvelope(message.content());
            if (!result.path("ok").asBoolean(false)) {
                continue;
            }
            String name = result.path("tool").asText();
            JsonNode data = result.path("untrusted_data");
            if (isSearchProgressResult(name, data)) {
                searchExecuted = true;
            }
            if (SubmitRecommendationsTool.TOOL.equals(name)
                    && data.path("recommendations").isArray()
                    && !data.path("recommendations").isEmpty()) {
                anyApproved = true;
            }
        }
        if (!searchExecuted || anyApproved) {
            return content;
        }
        var progress = JobSearchWorkflow.inspect(transcript, objectMapper);
        return "状态：尚未经推荐验收，以下不是正式岗位推荐；本轮采集候选 "
                + progress.uniqueJobs() + " 个、核实完整 JD "
                + progress.verifiedIds().size() + " 个。\n" + content;
    }

    private static boolean isSearchProgressResult(String toolName, JsonNode data) {
        if ("collect_boss_jobs".equals(toolName)) {
            return data.path("jobIds").isArray() && !data.path("jobIds").isEmpty();
        }
        if ("rank_jobs_for_resume".equals(toolName)) {
            return (data.path("items").isArray() && !data.path("items").isEmpty())
                    || (data.path("matches").isArray() && !data.path("matches").isEmpty());
        }
        return "verify_boss_job".equals(toolName)
                && "FULL".equals(data.path("captureDepth").asText());
    }

    String sanitizeAnswer(String content) {
        if (content == null || content.isBlank() || content.contains("\"untrusted_data\"")) {
            return AgentLoop.LIMIT_FALLBACK;
        }
        try {
            JsonNode parsed = objectMapper.readTree(content);
            if (parsed != null && parsed.isContainerNode()) {
                return AgentLoop.LIMIT_FALLBACK;
            }
        } catch (JsonProcessingException ex) {
            // Ordinary prose is not JSON.
        }
        return content;
    }

    static List<AgentMessage> currentResults(List<AgentMessage> transcript) {
        int start = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (AgentMessage.USER.equals(transcript.get(i).role())) {
                start = i + 1;
                break;
            }
        }
        List<AgentMessage> results = new ArrayList<>();
        for (int i = start; i < transcript.size(); i++) {
            AgentMessage message = transcript.get(i);
            if (AgentMessage.TOOL.equals(message.role())) {
                results.add(message);
            }
        }
        return results;
    }

    private JsonNode readEnvelope(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new IllegalStateException("invalid server tool envelope", ex);
        }
    }
}
