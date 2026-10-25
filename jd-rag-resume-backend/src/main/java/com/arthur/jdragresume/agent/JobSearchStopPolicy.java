package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;

/** The single policy for deciding whether another BOSS listing search is permitted. */
public final class JobSearchStopPolicy {
    private JobSearchStopPolicy() { }

    /** Shared by the system prompt and the collection tool description. */
    public static String guidance() {
        return "BOSS 采集正常结束后，如果没有结果、候选不足或与简历方向不符，"
                + "在剩余预算内自主调整关键词补搜，不需要再次询问用户，也不得重复相同条件或放宽硬性限制。"
                + "遇到登录失败、安全验证、用户取消、采集失败或页数预算耗尽，立即停止采集并说明原因。"
                + "第一页就返回 NO_MORE_RESULTS 视为疑似未登录，停止采集并提示检查 BOSS 登录状态，"
                + "不要连续换关键词消耗额度。";
    }

    /**
     * Only the existing finishReason / stopReason values are inspected. This returns an
     * instruction to halt future collection, never an additional protocol reason field.
     */
    static String haltReason(JsonNode result) {
        if (!result.path("ok").asBoolean(false)) {
            String code = result.path("error").path("code").asText("UNKNOWN");
            return "采集失败（" + code + "），停止自动采集并说明原始错误";
        }
        JsonNode data = result.path("untrusted_data");
        String stop = data.path("stopReason").asText("");
        if (!stop.isBlank() || "STOPPED".equals(data.path("status").asText())) {
            return "采集已中止（" + stop + "），停止自动采集并说明原因";
        }
        String finish = data.path("finishReason").asText("");
        if ("DAILY_LIMIT".equals(finish)) return "BOSS 今日页数额度已用完，停止采集";
        if ("NO_MORE_RESULTS".equals(finish) && data.path("pagesRead").asInt(-1) == 1) {
            return "第一页就 NO_MORE_RESULTS，疑似未登录 BOSS；停止补搜，请先检查登录状态";
        }
        return null;
    }
}
