package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

/** The single policy for deciding whether another BOSS listing search is permitted. */
public final class JobSearchStopPolicy {
    /**
     * Only known BOSS/platform/environment failures prohibit autonomous recollection.
     * Codes are emitted by the extension, browser-task lifecycle, and Patchright collectors.
     * Validation codes (BOSS_DIRECTION_REQUIRED, INVALID_ARGUMENTS, INVALID_COMMAND)
     * intentionally do not belong here: the model can repair the request.
     */
    static final Set<String> PLATFORM_BLOCKING_ERRORS = Set.of(
            "EXTENSION_NOT_FOUND", "EXTENSION_OUTDATED", "EXTENSION_RELOADED",
            "WORKER_UNREACHABLE", "PERMISSION_REQUIRED", "BOSS_NOT_LOGGED_IN",
            "CAPTCHA", "STRUCTURE_CHANGED", "PAGE_LOAD_TIMEOUT", "TAB_CLOSED",
            "UNEXPECTED_PAGE", "WORKER_RESTARTED", "USER_CANCELLED",
            "DAILY_LIMIT", "INTERNAL_ERROR", "DETAIL_UNAVAILABLE",
            "BUSY", "UNKNOWN_TASK", "UNKNOWN_ACTION",
            "PATCHRIGHT_UNAVAILABLE", "PATCHRIGHT_TIMEOUT", "PATCHRIGHT_INTERRUPTED",
            "PATCHRIGHT_REPORT_INVALID", "BROWSER_REPORT_INVALID",
            "BROWSER_TASK_EXPIRED", "BROWSER_TASK_UNKNOWN",
            "BROWSER_TASK_CANCELLED", "BROWSER_TASK_ABANDONED");

    private JobSearchStopPolicy() { }

    /** Shared by the system prompt and the collection tool description. */
    public static String guidance() {
        return "BOSS 采集正常结束后，如果没有结果、候选不足或与简历方向不符，"
                + "在剩余预算内自主调整关键词补搜，不需要再次询问用户，也不得重复相同条件或放宽硬性限制。"
                + "遇到明确的平台或环境故障（登录、授权、安全验证、采集服务、浏览器任务中断）或页数预算耗尽，立即停止并说明原因。"
                + "第一页就返回 NO_MORE_RESULTS 视为疑似未登录，停止采集并提示检查 BOSS 登录状态，"
                + "不要连续换关键词消耗额度。参数校验错误应先修正参数再尝试，不要误判为平台故障。";
    }

    /**
     * Only known platform error codes or existing finishReason / stopReason values halt
     * a collection. This never adds a new browser protocol field.
     */
    static String haltReason(JsonNode result) {
        if (!result.path("ok").asBoolean(false)) {
            String code = result.path("error").path("code").asText("");
            return PLATFORM_BLOCKING_ERRORS.contains(code)
                    ? "采集遇到平台或环境故障（" + code + "），停止自动采集并说明原始错误"
                    : null;
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
