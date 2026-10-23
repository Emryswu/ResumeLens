package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP client for the local persistent-browser service. It uses the extension's report validator. */
@Component
public class PatchrightJobCollector implements JobCollector {
    private final URI base;
    private final String token;
    private final ObjectMapper json;
    private final BrowserExtensionJobCollector reports;
    private final BossPageBudget budget;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public PatchrightJobCollector(@Value("${app.collector.url:http://127.0.0.1:3108}") String url,
                                 @Value("${app.collector.token:${COLLECTOR_TOKEN:}}") String token,
                                 ObjectMapper json, BrowserExtensionJobCollector reports, BossPageBudget budget) {
        this.base = URI.create(url);
        this.token = token;
        this.json = json;
        this.reports = reports;
        this.budget = budget;
    }

    @Override public String platform() { return "BOSS"; }
    @Override public Start start(CollectRequest request) { throw new IllegalStateException("collection session is required"); }
    @Override public CollectionResult complete(CollectRequest request, JsonNode report) { return reports.complete(request, report); }

    @Override public Start start(CollectRequest request, CollectionSession session) {
        var command = new LinkedHashMap<>(((Delegated) reports.start(request)).command());
        var grant = budget.reserve(session, session.reservationId(), request.maxPages());
        command.put("pageAllowance", grant.pages());
        command.put("budgetDate", grant.date().toString());
        JsonNode report = runAction(command, session, null);
        var result = reports.complete(request, report);
        int left = budget.finish(session, grant.key(), loadedPages(report));
        return new Collected(result.withDiagnostics(left, null));
    }

    JsonNode runAction(Map<String, Object> command, CollectionSession session, String switchReason) {
        session.announce(command, json.valueToTree(Map.of("phase", "connecting", "pagesRead", 0, "pageLog", java.util.List.of(), "jobs", java.util.List.of())), switchReason);
        if (token.isBlank()) throw new BusinessException("PATCHRIGHT_UNAVAILABLE", "独立浏览器服务尚未配置 COLLECTOR_TOKEN");
        String taskId = "pr_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var payload = new LinkedHashMap<String, Object>(command);
        payload.put("taskId", taskId);
        payload.put("conversationId", session.conversationId());
        payload.put("userId", session.userId());
        JsonNode initial = request("POST", "/tasks", payload);
        if ("rejected".equals(initial.path("type").asText())) return initial;
        long deadline = Math.min(session.deadline(), System.currentTimeMillis() + 60_000);
        JsonNode previous = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                JsonNode response = request("GET", "/tasks/" + taskId, null);
                JsonNode task = response.path("task");
                if (!task.isObject()) throw new BusinessException("PATCHRIGHT_REPORT_INVALID", "独立浏览器服务没有返回任务状态");
                if (!task.equals(previous)) {
                    session.announce(command, task, switchReason);
                    previous = task;
                }
                if (!"running".equals(task.path("status").asText())) return response;
                Thread.sleep(500);
            }
            throw new BusinessException("PATCHRIGHT_TIMEOUT", "独立浏览器采集超时，已请求停止");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("PATCHRIGHT_INTERRUPTED", "独立浏览器采集被中断");
        } finally {
            if (previous == null || "running".equals(previous.path("status").asText())) request("POST", "/tasks/" + taskId + "/cancel", Map.of());
        }
    }

    static Integer loadedPages(JsonNode report) {
        if ("rejected".equals(report.path("type").asText())) {
            // A lost worker may have received the command before the connection disappeared.
            String code = report.path("code").asText();
            return java.util.Set.of("WORKER_UNREACHABLE", "EXTENSION_RELOADED").contains(code) ? null : 0;
        }
        JsonNode count = report.path("task").path("pagesLoaded");
        if (count.isMissingNode()) return null; // An older extension cannot prove which unused pages can be released.
        if (!count.isIntegralNumber() || !count.canConvertToInt()) throw new BusinessException("BROWSER_REPORT_INVALID", "实际加载页数必须是整数");
        return count.asInt();
    }

    private JsonNode request(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new BusinessException("PATCHRIGHT_UNAVAILABLE", "独立浏览器服务请求失败（HTTP " + response.statusCode() + "）");
            if (response.body().length() > 200_000) throw new BusinessException("PATCHRIGHT_REPORT_INVALID", "独立浏览器返回结果过大");
            return json.readTree(response.body());
        } catch (java.io.IOException ex) {
            throw new BusinessException("PATCHRIGHT_UNAVAILABLE", "无法连接独立浏览器服务，请先启动本地采集服务");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("PATCHRIGHT_INTERRUPTED", "独立浏览器请求被中断");
        }
    }
}
