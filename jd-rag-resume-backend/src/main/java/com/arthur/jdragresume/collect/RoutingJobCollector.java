package com.arthur.jdragresume.collect;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Code owns routing. Platform/login restrictions and partially read results never trigger a second browser. */
@Component
@Primary
public class RoutingJobCollector implements JobCollector {
    private static final Set<String> FALLBACK_CODES = Set.of("EXTENSION_NOT_FOUND", "EXTENSION_OUTDATED", "EXTENSION_RELOADED",
            "WORKER_UNREACHABLE", "INTERNAL_ERROR", "PAGE_LOAD_TIMEOUT", "TAB_CLOSED", "UNEXPECTED_PAGE", "WORKER_RESTARTED");
    private final BrowserExtensionJobCollector extension;
    private final PatchrightJobCollector patchright;
    private final BossPageBudget budget;

    public RoutingJobCollector(BrowserExtensionJobCollector extension, PatchrightJobCollector patchright, BossPageBudget budget) {
        this.extension = extension;
        this.patchright = patchright;
        this.budget = budget;
    }

    @Override public String platform() { return "BOSS"; }
    @Override public Start start(CollectRequest request) { throw new IllegalStateException("collection session is required"); }
    @Override public CollectionResult complete(CollectRequest request, JsonNode report) { throw new IllegalStateException("collection session is required"); }

    @Override public Start start(CollectRequest request, CollectionSession session) {
        var command = ((Delegated) extension.start(request)).command();
        Action action = startAction(command, session);
        if (action.report() == null) return new Delegated("browser-extension", "collectBossJobs", action.command());
        CollectionResult result = extension.complete(request, action.report());
        int left = budget.finish(session, session.reservationId(), PatchrightJobCollector.loadedPages(action.report()));
        return new Collected(result.withDiagnostics(left, null));
    }

    @Override public CollectionResult complete(CollectRequest request, JsonNode report, CollectionSession session) {
        CollectionResult original = extension.complete(request, report);
        Action action = completeAction(((Delegated) extension.start(request)).command(), report, session);
        CollectionResult result = action.switchReason() == null ? original : extension.complete(request, action.report());
        return result.withDiagnostics(action.dailyPagesLeft(), action.switchReason());
    }

    /** Used by detail verification too, after JobSearchWorkflow has admitted the tool call. */
    public Action startAction(Map<String, Object> rawCommand, CollectionSession session) {
        int pages = "detail".equals(rawCommand.get("mode")) ? 1 : ((Number) rawCommand.get("maxPages")).intValue();
        var grant = budget.reserve(session, session.reservationId(), pages);
        var command = new LinkedHashMap<>(rawCommand);
        command.put("pageAllowance", grant.pages());
        command.put("budgetDate", grant.date().toString());
        command.put("conversationId", session.conversationId());
        if (session.mode() != CollectorMode.PATCHRIGHT) return new Action(command, null, null, grant.dailyPagesLeft());
        JsonNode report = patchright.runAction(command, session, null);
        return new Action(command, report, null, grant.dailyPagesLeft());
    }

    public Action completeAction(Map<String, Object> command, JsonNode report, CollectionSession session) {
        String code = "rejected".equals(report.path("type").asText()) ? report.path("code").asText()
                : report.path("task").path("stopReason").asText();
        Integer loaded = PatchrightJobCollector.loadedPages(report);
        int left = budget.finish(session, session.reservationId(), loaded);
        // Only a definite failure before any page was loaded can reuse this attempt's page allowance safely.
        if (session.mode() != CollectorMode.AUTO || !FALLBACK_CODES.contains(code) || loaded == null || loaded != 0) {
            return new Action(command, report, null, left);
        }
        String message = "我的 Chrome 不可用（" + fallbackMessage(code) + "），已切换到独立浏览器";
        int pages = "detail".equals(command.get("mode")) ? 1 : ((Number) command.get("maxPages")).intValue();
        var grant = budget.reserve(session, session.reservationId() + ":patchright", pages);
        var next = new LinkedHashMap<>(command);
        next.put("pageAllowance", grant.pages());
        next.put("budgetDate", grant.date().toString());
        JsonNode replacement = patchright.runAction(next, session, message);
        int remaining = budget.finish(session, grant.key(), PatchrightJobCollector.loadedPages(replacement));
        return new Action(next, replacement, message, remaining);
    }

    private String fallbackMessage(String code) {
        return BrowserExtensionJobCollector.REJECTIONS.getOrDefault(code,
                BrowserExtensionJobCollector.STOP_MESSAGES.getOrDefault(code, "插件连接失败"));
    }

    public record Action(Map<String, Object> command, JsonNode report, String switchReason, int dailyPagesLeft) { }
}
