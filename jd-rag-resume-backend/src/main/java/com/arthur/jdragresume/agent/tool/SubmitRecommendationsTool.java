package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobAvailabilityMatcher;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recommendation completion gate. Reads the owned, persisted FULL JD (including jobs verified
 * in earlier turns) instead of trusting tool-call history or clipped get_job previews.
 */
@Component
public class SubmitRecommendationsTool implements AgentTool {
    public static final String TOOL = "submit_recommendations";

    private final JobDescriptionService jobs;
    private final JobSearchPreferencesService preferences;

    public SubmitRecommendationsTool(JobDescriptionService jobs, JobSearchPreferencesService preferences) {
        this.jobs = jobs;
        this.preferences = preferences;
    }

    @Override public String name() { return "submit_recommendations"; }

    @Override public String description() {
        return "正式推荐岗位前必须调用此工具提交 recommendations 数组，每项包含 jobId、reason、evidence。"
                + "evidence 至少 8 个字符，来自 FULL JD 正文或任职要求（允许空白格式不同），不能只引用职位标题。"
                + "服务端逐条检查完整 JD、实际证据和当前求职硬条件：通过的保留，不通过的附具体原因。"
                + "UNKNOWN 会标成需向 HR 确认；下一次只需重提未通过条目，最多修正一次。";
    }

    @Override public ToolSchema parameters() {
        return ToolSchema.object().objectArray("recommendations", "准备正式推荐的岗位和 JD 证据",
                true, 5, ToolSchema.object()
                        .integer("jobId", "入库岗位 id", true, 1, Long.MAX_VALUE)
                        .string("reason", "为什么推荐该岗位（中文）", true, 240)
                        .string("evidence", "从 FULL JD 原文逐字复制的证据片段", true, 300));
    }

    @Override public Object execute(JsonNode arguments, AgentToolContext context) {
        var own = preferences.forUser(context.user().getId());
        List<Submitted> approved = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int position = 0;
        for (JsonNode proposal : arguments.get("recommendations")) {
            position++;
            long id = proposal.get("jobId").asLong();
            String reason = proposal.get("reason").asText().strip();
            String evidence = proposal.get("evidence").asText().strip();
            String label = "第" + position + "条岗位";
            if (!seen.add(id)) {
                rejected.add(new Rejected(id, label, "重复提交"));
                continue;
            }
            if (reason.isEmpty()) {
                rejected.add(new Rejected(id, label, "缺少推荐理由"));
                continue;
            }
            if (normalize(evidence).length() < 8) {
                rejected.add(new Rejected(id, label, "JD 证据片段至少需要 8 个字符"));
                continue;
            }
            JobDescriptionResponse job;
            try {
                // findById checks the authenticated owner, including older FULL records.
                job = jobs.findById(id);
            } catch (ResourceNotFoundException ex) {
                rejected.add(new Rejected(id, label, "岗位不存在或无权访问"));
                continue;
            }
            label = job.title();
            if (!"FULL".equals(job.captureDepth())) {
                rejected.add(new Rejected(id, label, "尚无 FULL JD，请先核实"));
                continue;
            }
            String original = normalize((job.description() == null ? "" : job.description()) + "\n"
                    + (job.requirements() == null ? "" : job.requirements()));
            String normalizedEvidence = normalize(evidence);
            if (normalize(job.title()).contains(normalizedEvidence)) {
                rejected.add(new Rejected(id, label, "引用的只是职位标题，缺少 JD 正文证据"));
                continue;
            }
            if (!original.contains(normalizedEvidence)) {
                rejected.add(new Rejected(id, label, "引用片段不在已入库的 JD 原文中"));
                continue;
            }
            var check = JobAvailabilityMatcher.compare(own, job.captureDepth(),
                    job.description(), job.requirements(), job.location());
            if (check.hardBlock()) {
                rejected.add(new Rejected(id, label, "与已填写的求职硬条件冲突"));
                continue;
            }
            List<String> unknown = new ArrayList<>();
            if ("UNKNOWN".equals(check.duration().status())) unknown.add("实习时长");
            if ("UNKNOWN".equals(check.attendance().status())) unknown.add("每周到岗天数");
            if ("UNKNOWN".equals(check.district().status())) unknown.add("地区");
            if ("UNKNOWN".equals(check.startDate().status())) unknown.add("最早到岗日期需与 HR 确认");
            if (!own.configured()) unknown.add("用户尚未配置求职条件");
            approved.add(new Submitted(id, job.title(), job.companyName(), job.location(),
                    reason, evidence, List.copyOf(unknown)));
        }
        return new Result(rejected.isEmpty(), List.copyOf(approved), List.copyOf(rejected));
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    @Override public int resultMaxChars(int configuredLimit) {
        // Five valid proposals can exceed the default 4k response ceiling; the loop finishes immediately on acceptance.
        return Math.max(configuredLimit, 12_000);
    }

    @Override public Object stepFacts(Object result) {
        Result r = (Result) result;
        return java.util.Map.of("accepted", r.accepted(),
                "recommended", r.recommendations().size(), "rejected", r.rejected().size());
    }

    public record Submitted(long jobId, String title, String company, String location,
                            String reason, String evidence, List<String> unknownConditions) { }
    public record Rejected(long jobId, String title, String reason) { }
    public record Result(boolean accepted, List<Submitted> recommendations, List<Rejected> rejected) { }
}
