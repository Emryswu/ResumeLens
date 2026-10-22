package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Collects BOSS search listings through the ResumeLens browser extension. The server cannot
 * open BOSS itself, so {@link #start} only validates the search and delegates it to the page,
 * which hands it to the extension; the extension's final event comes back to {@link #complete}.
 *
 * <p>That event travels through the user's browser, so it is untrusted: it must be for the
 * search that was asked for, every field is bounded, URLs must point at BOSS job pages, and
 * the messages the model reads come from the tables here, never from the report.
 */
@Component
public class BrowserExtensionJobCollector implements JobCollector {
    public static final String EXECUTOR = "browser-extension";
    public static final String ACTION = "collectBossJobs";

    /** Mirror browser-extension/src/boss-search.js (CITY_CODES and LIMITS); a test compares them. */
    public static final List<String> CITIES = List.of(
            "全国", "北京", "上海", "广州", "深圳", "杭州", "南京", "苏州",
            "成都", "武汉", "西安", "天津", "重庆", "长沙", "厦门");
    static final int MAX_PAGES = 3;
    static final int MAX_JOBS = 45;
    static final int KEYWORD_MAX_CHARS = 50;

    private static final Pattern CODE = Pattern.compile("[A-Z_]{1,40}");
    private static final Pattern JOB_ID = Pattern.compile("[^\\s/?#]{1,160}");
    private static final Set<String> CAPTURE_SOURCES = Set.of("api", "dom");

    static final Map<String, String> FINISH_MESSAGES = Map.of(
            "MAX_PAGES", "已读完要求的页数",
            "NO_MORE_RESULTS", "列表没有更多结果了",
            "NO_RESULTS", "这次搜索没有找到岗位",
            "MAX_JOBS", "已达到单次最多 " + MAX_JOBS + " 条的上限",
            "DAILY_LIMIT", "插件今天的采集页数已用完，读到的岗位已保留");

    static final Map<String, String> STOP_MESSAGES = Map.ofEntries(
            Map.entry("PERMISSION_REQUIRED", "插件还没获得 BOSS 直聘的访问授权，需要用户在插件弹窗里允许访问 BOSS 直聘"),
            Map.entry("BOSS_NOT_LOGGED_IN", "BOSS 直聘未登录，需要用户先在这个浏览器里登录 BOSS 直聘"),
            Map.entry("CAPTCHA", "BOSS 直聘要求安全验证，插件已停止，需要用户手动完成验证"),
            Map.entry("STRUCTURE_CHANGED", "BOSS 搜索页结构和插件认识的不一致，插件已停止，可能需要更新插件"),
            Map.entry("PAGE_LOAD_TIMEOUT", "BOSS 搜索页加载超时，插件已停止"),
            Map.entry("TAB_CLOSED", "采集用的标签页被关闭，插件已停止"),
            Map.entry("UNEXPECTED_PAGE", "BOSS 搜索页跳转到了插件无法读取的页面，插件已停止"),
            Map.entry("WORKER_RESTARTED", "插件后台在采集途中被浏览器重启，插件已停止"),
            Map.entry("USER_CANCELLED", "用户在插件里停止了采集"),
            Map.entry("DAILY_LIMIT", "插件今天的采集页数已用完"),
            Map.entry("INTERNAL_ERROR", "插件内部错误，已停止采集"));

    /** Rejections from the extension, plus the ones the page reports when it cannot reach a usable extension. */
    static final Map<String, String> REJECTIONS = Map.ofEntries(
            Map.entry("EXTENSION_NOT_FOUND", "没有检测到 ResumeLens 浏览器插件，需要用户在这个浏览器里安装并启用插件"),
            Map.entry("EXTENSION_OUTDATED", "浏览器插件版本过旧，不支持采集，需要用户更新插件"),
            Map.entry("EXTENSION_RELOADED", "插件刚被重新加载，需要用户刷新 ResumeLens 网页后再试"),
            Map.entry("WORKER_UNREACHABLE", "插件后台没有响应，需要用户在 chrome://extensions 里确认插件已启用"),
            Map.entry("BUSY", "插件正在执行另一次采集"),
            Map.entry("DAILY_LIMIT", "插件今天的采集页数已用完，明天再试"),
            Map.entry("INVALID_COMMAND", "插件认为采集参数不正确"),
            Map.entry("UNKNOWN_TASK", "插件里找不到这次采集任务"),
            Map.entry("UNKNOWN_ACTION", "插件不支持这条指令，可能需要更新插件"),
            Map.entry("INTERNAL_ERROR", "插件内部错误"));

    @Override
    public String platform() {
        return "BOSS";
    }

    @Override
    public Start start(CollectRequest request) {
        if (request.keyword().isEmpty() || request.keyword().length() > KEYWORD_MAX_CHARS) {
            throw invalidRequest("keyword 不能为空，且不超过 " + KEYWORD_MAX_CHARS + " 个字符");
        }
        if (!CITIES.contains(request.city())) {
            throw invalidRequest("暂不支持城市「" + request.city() + "」，可选：" + String.join("、", CITIES));
        }
        if (request.maxPages() < 1 || request.maxPages() > MAX_PAGES) {
            throw invalidRequest("maxPages 必须是 1 到 " + MAX_PAGES + " 之间的整数");
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("keyword", request.keyword());
        command.put("city", request.city());
        command.put("maxPages", request.maxPages());
        return new Delegated(EXECUTOR, ACTION, command);
    }

    @Override
    public CollectionResult complete(CollectRequest request, JsonNode report) {
        if (report == null || !report.isObject()) {
            throw invalidReport("report is not an object");
        }
        String type = report.path("type").asText("");
        if ("rejected".equals(type)) {
            String code = report.path("code").asText("");
            String message = REJECTIONS.get(code);
            return message != null
                    ? CollectionResult.failed(code, message)
                    : CollectionResult.failed("EXTENSION_REJECTED", "插件拒绝了这次采集（" + safeCode(code) + "）");
        }
        if (!"result".equals(type)) {
            throw invalidReport("unknown report type");
        }
        JsonNode task = report.path("task");
        requireSameSearch(request, task.path("command"));
        int pagesRead = integer(task.path("pagesRead"), 0, MAX_PAGES, "pagesRead");
        int skipped = integer(task.path("skippedWithoutId"), 0, 10_000, "skippedWithoutId");
        Integer dailyPagesLeft = task.path("dailyPagesLeft").isIntegralNumber()
                ? integer(task.path("dailyPagesLeft"), 0, 10_000, "dailyPagesLeft") : null;
        List<CollectedListing> listings = listings(task.path("jobs"));
        String status = task.path("status").asText("");
        if ("completed".equals(status)) {
            String code = task.path("finishReason").asText("");
            String message = FINISH_MESSAGES.get(code);
            if (message == null) {
                throw invalidReport("unknown finishReason");
            }
            return new CollectionResult(CollectionResult.Outcome.COMPLETED, code, message, pagesRead, listings, skipped, dailyPagesLeft);
        }
        if ("stopped".equals(status)) {
            String code = task.path("stopReason").asText("");
            if (!CODE.matcher(code).matches()) {
                throw invalidReport("invalid stopReason");
            }
            String message = STOP_MESSAGES.getOrDefault(code, "插件停止了采集（" + code + "）");
            return new CollectionResult(CollectionResult.Outcome.STOPPED, code, message, pagesRead, listings, skipped, dailyPagesLeft);
        }
        throw invalidReport("unknown task status");
    }

    /** The extension echoes the command it ran; a report for any other search is refused. */
    private static void requireSameSearch(CollectRequest request, JsonNode command) {
        CollectRequest reported = new CollectRequest(
                command.path("keyword").asText(""),
                command.path("city").asText(""),
                command.path("maxPages").isIntegralNumber() ? command.path("maxPages").asInt() : -1);
        if (!reported.equals(request)) {
            throw invalidReport("report is for a different search");
        }
    }

    private static List<CollectedListing> listings(JsonNode jobs) {
        if (!jobs.isArray() || jobs.size() > MAX_JOBS) {
            throw invalidReport("jobs must be an array of at most " + MAX_JOBS);
        }
        List<CollectedListing> listings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode job : jobs) {
            if (!job.isObject() || !"BOSS".equals(job.path("sourcePlatform").asText("").toUpperCase(Locale.ROOT))) {
                throw invalidReport("job is not a BOSS listing");
            }
            String sourceJobId = text(job, "sourceJobId", 160, true);
            if (!JOB_ID.matcher(sourceJobId).matches()) {
                throw invalidReport("invalid sourceJobId");
            }
            String sourceUrl = text(job, "sourceUrl", 2048, true);
            requireBossJobUrl(sourceUrl);
            String captureSource = job.path("captureSource").asText("dom");
            if (!CAPTURE_SOURCES.contains(captureSource)) {
                throw invalidReport("invalid captureSource");
            }
            CollectedListing listing = new CollectedListing(
                    text(job, "title", 160, true),
                    text(job, "companyName", 120, true),
                    text(job, "location", 80, false),
                    text(job, "employmentType", 60, false),
                    text(job, "description", 20_000, true),
                    text(job, "requirements", 20_000, false),
                    sourceUrl,
                    sourceJobId,
                    captureSource);
            if (seen.add(sourceJobId)) {
                listings.add(listing);
            }
        }
        return listings;
    }

    private static void requireBossJobUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean boss = host.equals("zhipin.com") || host.endsWith(".zhipin.com");
            if (!"https".equals(uri.getScheme()) || !boss || uri.getPath() == null || !uri.getPath().startsWith("/job_detail/")) {
                throw invalidReport("sourceUrl is not a BOSS job page");
            }
        } catch (java.net.URISyntaxException ex) {
            throw invalidReport("sourceUrl is not a URL");
        }
    }

    private static String text(JsonNode job, String field, int maxLength, boolean required) {
        JsonNode value = job.get(field);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            if (required) {
                throw invalidReport(field + " is required");
            }
            return null;
        }
        if (!value.isTextual() || value.asText().trim().length() > maxLength) {
            throw invalidReport(field + " must be text of at most " + maxLength + " characters");
        }
        return value.asText().trim();
    }

    private static int integer(JsonNode value, int min, int max, String field) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < min || value.asInt() > max) {
            throw invalidReport(field + " must be an integer between " + min + " and " + max);
        }
        return value.asInt();
    }

    private static String safeCode(String code) {
        return CODE.matcher(code).matches() ? code : "UNKNOWN";
    }

    private static BusinessException invalidRequest(String message) {
        return new BusinessException("INVALID_ARGUMENTS", message);
    }

    /** The detail stays in the code path for debugging; the model only needs to know the report was dropped. */
    private static BusinessException invalidReport(String detail) {
        return new BusinessException("BROWSER_REPORT_INVALID", "浏览器返回的采集结果格式不正确（" + detail + "），已丢弃，没有保存任何岗位");
    }
}
