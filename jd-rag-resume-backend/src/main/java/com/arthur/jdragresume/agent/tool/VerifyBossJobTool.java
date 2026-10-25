package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobCaptureRequest;
import com.arthur.jdragresume.dto.job.JobCaptureResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.Map;

/**
 * Promotes an existing BOSS list summary to FULL only after the user's extension has actually
 * opened that same posting and sent back its description. The browser report is untrusted.
 */
@Component
public class VerifyBossJobTool implements DeferrableTool {
    private final JobDescriptionService jobs;
    private final com.arthur.jdragresume.collect.RoutingJobCollector collectors;

    public VerifyBossJobTool(JobDescriptionService jobs, com.arthur.jdragresume.collect.RoutingJobCollector collectors) {
        this.jobs = jobs;
        this.collectors = collectors;
    }

    @Override
    public String name() {
        return "verify_boss_job";
    }

    @Override
    public String description() {
        return "把职位库中的一个 BOSS 列表摘要升级为已核实完整 JD：让用户的浏览器插件打开对应职位详情页，只读抓取正文并核对岗位身份。"
                + "应先用 rank_jobs_for_resume 粗排，再对靠前、与简历方向相符的少数岗位调用。"
                + "成功后调用 get_job 读取全文，核查学历、实习时长、每周到岗天数、技术栈、职责及硬性要求；未出现的条件要写未知。"
                + "只读，不打招呼；失败就保留原有 LISTING 摘要，不能称为完整 JD。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object().integer("jobId", "来自 search_jobs 或 rank_jobs_for_resume 的职位 id", true, 1, Long.MAX_VALUE);
    }

    @Override
    public Begin begin(JsonNode arguments, AgentToolContext context) {
        // beforeBudget validated the source and URL. A LISTING may be loaded again here.
        JobDescriptionResponse job = loadBeforeBrowser(arguments.get("jobId").asLong());
        Map<String, Object> command = Map.of("mode", "detail", "jobId", job.id(), "sourceJobId", job.sourceJobId(), "sourceUrl", job.sourceUrl());
        var action = collectors.startAction(command, context.collection());
        if (action.report() == null) return new InBrowser("browser-extension", "verifyBossJob", action.command());
        collectors.completeAction(command, action.report(), context.collection());
        return new Done(saveReport(job, action.report()));
    }

    /**
     * Check the posting before charging a browser attempt. A persisted FULL JD is a
     * read-only success, while invalid jobs never start a browser task.
     */
    @Override
    public Done beforeBudget(JsonNode arguments) {
        JobDescriptionResponse job = loadBeforeBrowser(arguments.get("jobId").asLong());
        requireBoss(job);
        requireBossUrl(job.sourceUrl(), job.sourceJobId());
        return "FULL".equals(job.captureDepth()) ? alreadyFullResult(job) : null;
    }

    private JobDescriptionResponse loadBeforeBrowser(long jobId) {
        try {
            return jobs.findById(jobId);
        } catch (ResourceNotFoundException ex) {
            // Distinct from NOT_FOUND during resume: a missing posting before opening
            // the browser must not charge an attempt or a daily-page estimate.
            throw new BusinessException("BOSS_VERIFY_JOB_NOT_FOUND", ex.getMessage() + "（id 必须来自工具结果）");
        }
    }

    private static Done alreadyFullResult(JobDescriptionResponse job) {
        return new Done(Map.of("jobId", job.id(), "captureDepth", "FULL",
                "alreadyVerified", true, "verificationStatus", "ALREADY_FULL",
                "next", "调用 get_job 读取完整正文并核实硬性条件"));
    }

    @Override
    @Transactional
    public Object resume(JsonNode arguments, JsonNode report, AgentToolContext context) {
        if (report == null || !report.isObject()) throw invalidReport();
        JobDescriptionResponse job = jobs.findById(arguments.get("jobId").asLong());
        // The browser already ran. A changed source or URL at resume time must not
        // be misclassified as a failed preflight and refunded by JobSearchWorkflow.
        try {
            requireBoss(job);
            requireBossUrl(job.sourceUrl(), job.sourceJobId());
        } catch (BusinessException ex) {
            throw invalidReport();
        }
        report = collectors.completeAction(Map.of("mode", "detail", "jobId", job.id(), "sourceJobId", job.sourceJobId(),
                "sourceUrl", job.sourceUrl()), report, context.collection()).report();
        return saveReport(job, report);
    }

    private Object saveReport(JobDescriptionResponse existing, JsonNode report) {
        if (report == null || !report.isObject()) throw invalidReport();
        if ("rejected".equals(report.path("type").asText())) {
            throw new BusinessException("BOSS_DETAIL_UNAVAILABLE", "插件拒绝了详情核实，原有列表摘要未改变");
        }
        JsonNode task = report.path("task");
        if (!"result".equals(report.path("type").asText())
                || task.path("command").path("jobId").asLong(-1) != existing.id()
                || !"detail".equals(task.path("command").path("mode").asText())
                || !existing.sourceJobId().equals(task.path("command").path("sourceJobId").asText())
                || !existing.sourceUrl().equals(task.path("command").path("sourceUrl").asText())) {
            throw invalidReport();
        }
        if ("stopped".equals(task.path("status").asText())) {
            throw new BusinessException("BOSS_DETAIL_UNAVAILABLE",
                    "详情核实被插件中止（" + safeReason(task.path("stopReason").asText()) + "），原有列表摘要未改变");
        }
        if (!"completed".equals(task.path("status").asText())
                || !"DETAIL_READ".equals(task.path("finishReason").asText())
                || !task.path("jobs").isArray() || task.path("jobs").size() != 1) {
            throw invalidReport();
        }
        JsonNode item = task.path("jobs").get(0);
        if (!"BOSS".equals(item.path("sourcePlatform").asText())
                || !existing.sourceJobId().equals(item.path("sourceJobId").asText())
                || !existing.sourceUrl().equals(item.path("sourceUrl").asText())) {
            throw invalidReport();
        }
        String description = requiredText(item, "description", 20_000);
        if (description.length() < 60 || description.contains("BOSS 搜索列表摘要")) {
            throw invalidReport();
        }
        JobCaptureResponse captured = jobs.capture(new JobCaptureRequest(
                requiredText(item, "title", 160),
                requiredText(item, "companyName", 120),
                optionalText(item, "location", 80),
                optionalText(item, "employmentType", 60),
                description,
                optionalText(item, "requirements", 20_000),
                "BOSS", existing.sourceUrl(), existing.sourceJobId()));
        // Only a confirmed FULL row for this job may produce a successful verification marker.
        if (!"FULL".equals(captured.job().captureDepth()) || !existing.id().equals(captured.job().id())) {
            throw invalidReport();
        }
        return Map.of("jobId", captured.job().id(), "captureDepth", "FULL", "verificationStatus", "SAVED_FULL",
                "contentChanged", captured.contentChanged(),
                "next", "调用 get_job 查看完整 JD，对照简历逐项核实门槛；未写明的条件保持未知");
    }

    private static void requireBoss(JobDescriptionResponse job) {
        if (!"BOSS".equalsIgnoreCase(job.sourcePlatform())) {
            throw new BusinessException("BOSS_DETAIL_UNSUPPORTED", "只能核实已采集的 BOSS 岗位");
        }
    }

    private static void requireBossUrl(String sourceUrl, String sourceJobId) {
        if (sourceUrl == null || sourceJobId == null) throw invalidBossUrl();
        try {
            URI uri = URI.create(sourceUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            if (!"https".equals(uri.getScheme()) || host == null ||
                    !(host.equals("zhipin.com") || host.endsWith(".zhipin.com")) ||
                    path == null || !path.matches("/job_detail/[^/]+\\.html/?") ||
                    !path.substring("/job_detail/".length()).replaceFirst("\\.html/?$", "").equals(sourceJobId) ||
                    uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw invalidBossUrl();
            }
        } catch (IllegalArgumentException ex) {
            throw invalidBossUrl();
        }
    }

    private static String requiredText(JsonNode item, String key, int max) {
        String value = optionalText(item, key, max);
        if (value == null || value.isBlank()) throw invalidReport();
        return value;
    }

    private static String optionalText(JsonNode item, String key, int max) {
        JsonNode value = item.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual() || value.asText().length() > max) throw invalidReport();
        return value.asText().trim();
    }

    private static String safeReason(String reason) {
        return reason.matches("[A-Z_]{1,40}") ? reason : "UNKNOWN";
    }

    private static BusinessException invalidBossUrl() {
        return new BusinessException("BOSS_DETAIL_URL_INVALID", "BOSS 职位详情链接不合法，未打开浏览器");
    }

    private static BusinessException invalidReport() {
        return new BusinessException("BOSS_DETAIL_INVALID", "BOSS 详情核实结果与请求不匹配或正文不完整，未修改职位库");
    }
}
