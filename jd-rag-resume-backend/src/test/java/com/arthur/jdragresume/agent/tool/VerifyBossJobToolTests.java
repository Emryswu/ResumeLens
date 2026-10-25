package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VerifyBossJobToolTests {
    private static final String URL = "https://www.zhipin.com/job_detail/abc123.html";
    private final ObjectMapper mapper = new ObjectMapper();
    private final JobDescriptionService jobs = mock(JobDescriptionService.class);
    private final VerifyBossJobTool tool = new VerifyBossJobTool(jobs, new com.arthur.jdragresume.collect.RoutingJobCollector(null, null, null) {
        @Override public Action startAction(Map<String, Object> command, com.arthur.jdragresume.collect.CollectionSession session) {
            return new Action(command, null, null, 30);
        }
        @Override public Action completeAction(Map<String, Object> command, JsonNode report, com.arthur.jdragresume.collect.CollectionSession session) {
            return new Action(command, report, null, 30);
        }
    });
    private final AgentToolContext context = new AgentToolContext(new AppUser());

    private JobDescriptionResponse job(String depth) {
        return new JobDescriptionResponse(17L, 1L, "Java 实习", "示例科技", "深圳",
                "实习", "在校生", "（BOSS 搜索列表摘要）", "本科",
                "BOSS", URL, "abc123", null, null, depth, null, null);
    }

    private ObjectNode arguments() {
        return mapper.createObjectNode().put("jobId", 17);
    }

    private ObjectNode report(String description) {
        ObjectNode report = mapper.createObjectNode().put("type", "result");
        ObjectNode task = report.putObject("task").put("status", "completed")
                .put("finishReason", "DETAIL_READ");
        task.putObject("command").put("jobId", 17).put("mode", "detail")
                .put("sourceJobId", "abc123").put("sourceUrl", URL);
        task.putArray("jobs").addObject().put("title", "Java 实习")
                .put("companyName", "示例科技").put("location", "深圳")
                .put("employmentType", "实习").put("description", description)
                .put("requirements", "本科、每周 5 天").put("sourcePlatform", "BOSS")
                .put("sourceUrl", URL).put("sourceJobId", "abc123");
        return report;
    }

    @Test
    void aListingDelegatesToTheUsersChromeWithBoundIdentity() {
        when(jobs.findById(17L)).thenReturn(job("LISTING"));
        var next = assertInstanceOf(DeferrableTool.InBrowser.class, tool.begin(arguments(), context));
        assertEquals("verifyBossJob", next.action());
        assertEquals(URL, next.command().get("sourceUrl"));
        assertEquals("abc123", next.command().get("sourceJobId"));
        verify(jobs, never()).capture(any());
    }

    @Test
    void anAlreadyFullPostingNeedsNoBrowserTask() {
        when(jobs.findById(17L)).thenReturn(job("FULL"));
        var next = assertInstanceOf(DeferrableTool.Done.class, tool.begin(arguments(), context));
        assertEquals("FULL", mapper.valueToTree(next.result()).path("captureDepth").asText());
        assertEquals("ALREADY_FULL", mapper.valueToTree(next.result()).path("verificationStatus").asText());
        assertEquals("ALREADY_FULL", mapper.valueToTree(tool.alreadyStoredFull(arguments()).result())
                .path("verificationStatus").asText());
        verify(jobs, never()).capture(any());
    }

    @Test
    void matchingFullDetailUpgradesThroughTheExistingDeduplicatingCaptureService() {
        when(jobs.findById(17L)).thenReturn(job("LISTING"));
        when(jobs.capture(any())).thenReturn(new JobCaptureResponse(job("FULL"), true, true));
        var output = mapper.valueToTree(tool.resume(arguments(), report("招聘本科 Java 后端实习生，每周到岗五天。".repeat(5)), context));
        assertEquals("FULL", output.path("captureDepth").asText());
        assertEquals("SAVED_FULL", output.path("verificationStatus").asText());
        var request = org.mockito.ArgumentCaptor.forClass(JobCaptureRequest.class);
        verify(jobs).capture(request.capture());
        assertEquals("abc123", request.getValue().sourceJobId());
        assertEquals(URL, request.getValue().sourceUrl());
        assertTrue(request.getValue().description().contains("每周到岗五天"));
    }

    @Test
    void aCaptureThatDidNotPersistFullCannotClaimVerification() {
        when(jobs.findById(17L)).thenReturn(job("LISTING"));
        when(jobs.capture(any())).thenReturn(new JobCaptureResponse(job("LISTING"), true, true));
        assertEquals("BOSS_DETAIL_INVALID", assertThrows(BusinessException.class, () ->
                tool.resume(arguments(), report("招聘本科 Java 后端实习生，每周到岗五天。".repeat(5)), context)).getCode());
        verify(jobs).capture(any());
    }

    @Test
    void aSpoofedIdentityOrShortSummaryMustNotOverwriteTheStoredListing() {
        when(jobs.findById(17L)).thenReturn(job("LISTING"));
        ObjectNode spoofed = report("岗位完整描述。".repeat(20));
        ((ObjectNode) spoofed.path("task").path("jobs").get(0)).put("sourceJobId", "other");
        assertEquals("BOSS_DETAIL_INVALID",
                assertThrows(BusinessException.class, () -> tool.resume(arguments(), spoofed, context)).getCode());
        assertEquals("BOSS_DETAIL_INVALID",
                assertThrows(BusinessException.class, () -> tool.resume(arguments(), report("摘要"), context)).getCode());
        verify(jobs, never()).capture(any());
    }

    @Test
    void aStoppedOrMissingBrowserReportDoesNotChangeCaptureDepth() {
        when(jobs.findById(17L)).thenReturn(job("LISTING"));
        ObjectNode stopped = report("岗位完整描述。".repeat(20));
        ((ObjectNode) stopped.path("task")).put("status", "stopped").put("stopReason", "CAPTCHA");
        assertEquals("BOSS_DETAIL_UNAVAILABLE",
                assertThrows(BusinessException.class, () -> tool.resume(arguments(), stopped, context)).getCode());
        assertEquals("BOSS_DETAIL_INVALID",
                assertThrows(BusinessException.class, () -> tool.resume(arguments(), null, context)).getCode());
        verify(jobs, never()).capture(any());
    }
}
