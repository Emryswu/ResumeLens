package com.arthur.jdragresume.collect;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutingJobCollectorTests {
    private final ObjectMapper json = new ObjectMapper();
    private final BrowserExtensionJobCollector extension = new BrowserExtensionJobCollector();
    private final PatchrightJobCollector patchright = mock(PatchrightJobCollector.class);
    private final BossPageBudget budget = mock(BossPageBudget.class);
    private final RoutingJobCollector router = new RoutingJobCollector(extension, patchright, budget);
    private final CollectRequest request = new CollectRequest("Java", "深圳", 1);

    private CollectionSession session(CollectorMode mode) {
        return new CollectionSession(1L, "42", "42:turn:call", "call", mode, Long.MAX_VALUE, progress -> {});
    }

    private void availableBudget() {
        when(budget.reserve(any(), anyString(), anyInt())).thenAnswer(call ->
                new BossPageBudget.Grant(call.getArgument(1), call.getArgument(2), 29));
        when(budget.finish(any(), anyString(), nullable(Integer.class))).thenReturn(29);
    }

    private JsonNode report(String reason, int loaded) {
        return json.valueToTree(Map.of("type", "result", "task", Map.of("status", "completed", "finishReason", reason,
                "command", Map.of("keyword", "Java", "city", "深圳", "maxPages", 1, "experience", "不限"),
                "pagesRead", 0, "pagesLoaded", loaded, "jobs", List.of(), "skippedWithoutId", 0, "pageLog", List.of())));
    }

    @Test void autoFallsBackForAMissingExtensionAndReportsTheCodeOwnedReason() {
        availableBudget();
        var session = session(CollectorMode.AUTO);
        assertInstanceOf(JobCollector.Delegated.class, router.start(request, session));
        when(patchright.runAction(anyMap(), eq(session), anyString())).thenReturn(report("NO_RESULTS", 1));
        CollectionResult result = router.complete(request, json.valueToTree(Map.of("type", "rejected", "code", "EXTENSION_NOT_FOUND")), session);
        assertEquals(CollectionResult.Outcome.COMPLETED, result.outcome());
        assertTrue(result.switchReason().contains("已切换到独立浏览器"));
        verify(budget).finish(session, session.reservationId(), 0);
        verify(budget).reserve(session, session.reservationId() + ":patchright", 1);
    }

    @Test void autoDoesNotSwitchWhenTheExtensionWorks() {
        availableBudget();
        var session = session(CollectorMode.AUTO);
        router.start(request, session);
        var result = router.complete(request, report("NO_RESULTS", 1), session);
        assertNull(result.switchReason());
        verifyNoInteractions(patchright);
    }

    @Test void explicitChromeDoesNotSwitchWhenTheExtensionIsMissing() {
        availableBudget();
        var session = session(CollectorMode.CHROME);
        router.start(request, session);
        assertEquals(CollectionResult.Outcome.FAILED, router.complete(request,
                json.valueToTree(Map.of("type", "rejected", "code", "EXTENSION_NOT_FOUND")), session).outcome());
        verifyNoInteractions(patchright);
    }

    @Test void explicitPatchrightDoesNotDelegateToChrome() {
        availableBudget();
        var session = session(CollectorMode.PATCHRIGHT);
        when(patchright.runAction(anyMap(), eq(session), isNull())).thenReturn(report("NO_RESULTS", 1));
        var collected = assertInstanceOf(JobCollector.Collected.class, router.start(request, session));
        assertNull(collected.result().switchReason());
        verify(patchright).runAction(anyMap(), eq(session), isNull());
    }

    @Test void patchrightSearchAndDetailAreBlockedBeforeHttpWhenTheSharedBudgetIsSpent() {
        when(budget.reserve(any(), anyString(), anyInt())).thenThrow(new BusinessException("JOB_SEARCH_BUDGET", "daily limit"));
        assertEquals("JOB_SEARCH_BUDGET", assertThrows(BusinessException.class,
                () -> router.start(request, session(CollectorMode.PATCHRIGHT))).getCode());
        assertThrows(BusinessException.class, () -> router.startAction(Map.of("mode", "detail"), session(CollectorMode.PATCHRIGHT)));
        verifyNoInteractions(patchright);
    }

    @Test void platformBlockersAndAlreadyLoadedPagesDoNotStartAnotherBrowser() {
        availableBudget();
        for (String reason : List.of("CAPTCHA", "BOSS_NOT_LOGGED_IN", "TAB_CLOSED")) {
            var report = (com.fasterxml.jackson.databind.node.ObjectNode) report("NO_RESULTS", reason.equals("TAB_CLOSED") ? 1 : 0);
            var task = (com.fasterxml.jackson.databind.node.ObjectNode) report.get("task");
            task.put("status", "stopped").put("stopReason", reason).remove("finishReason");
            router.complete(request, report, session(CollectorMode.AUTO));
        }
        verifyNoInteractions(patchright);
    }

    @Test void theRealAgentLoopBlocksTheFourthPatchrightCallBeforeItReachesTheCollector() {
        availableBudget();
        var jobs = mock(com.arthur.jdragresume.service.JobDescriptionService.class);
        when(jobs.captureListings(anyList(), anyString())).thenReturn(new com.arthur.jdragresume.dto.job.ListingCaptureResult(List.of(), 0, false, 200));
        when(patchright.runAction(anyMap(), any(), isNull())).thenAnswer(call -> {
            Map<String, Object> command = call.getArgument(0);
            return json.valueToTree(Map.of("type", "result", "task", Map.of("status", "completed", "finishReason", "NO_RESULTS",
                    "command", command, "pagesRead", 0, "pagesLoaded", 1, "jobs", List.of(), "skippedWithoutId", 0, "pageLog", List.of())));
        });
        var tool = new com.arthur.jdragresume.agent.tool.CollectBossJobsTool(router, jobs, mock(JobCollectionHistory.class));
        var properties = new com.arthur.jdragresume.agent.AgentProperties();
        properties.setMaxSteps(10);
        properties.setMaxPlanningSteps(10);
        com.arthur.jdragresume.agent.AgentModel model = (system, transcript, tools, timeout) -> {
            long results = transcript.stream().filter(message -> com.arthur.jdragresume.agent.AgentMessage.TOOL.equals(message.role())).count();
            return results == 4 ? com.arthur.jdragresume.agent.AgentModel.Reply.answer("预算已到")
                    : com.arthur.jdragresume.agent.AgentModel.Reply.call(com.arthur.jdragresume.agent.AgentMessage.ToolCall.of("c" + results,
                    "collect_boss_jobs", "{\"keyword\":\"Java" + results + "\",\"city\":\"深圳\",\"maxPages\":1}"));
        };
        var loop = new com.arthur.jdragresume.agent.AgentLoop(model, new com.arthur.jdragresume.agent.tool.AgentToolRegistry(List.of(tool)),
                json, properties, System::currentTimeMillis);
        var normalized = new com.arthur.jdragresume.agent.TranscriptPolicy(properties).normalize(
                List.of(com.arthur.jdragresume.agent.AgentMessage.user("帮我找深圳岗位")), null);
        var user = new com.arthur.jdragresume.entity.AppUser();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", 1L);
        var run = new com.arthur.jdragresume.agent.ConversationStore.Run(1, 42, "turn", "run", normalized, null, System.currentTimeMillis());
        var result = loop.run(normalized, null, new com.arthur.jdragresume.agent.tool.AgentToolContext(user, run, CollectorMode.PATCHRIGHT),
                (name, data) -> {}, () -> false);
        assertEquals(com.arthur.jdragresume.agent.AgentLoop.State.COMPLETED, result.state());
        verify(patchright, times(3)).runAction(anyMap(), any(), isNull());
        assertTrue(result.transcript().stream().anyMatch(message -> message.content() != null && message.content().contains("JOB_SEARCH_BUDGET")));
    }
}
