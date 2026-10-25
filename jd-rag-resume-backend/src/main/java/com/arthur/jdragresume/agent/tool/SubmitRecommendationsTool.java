package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.exception.BusinessException;
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
                + "evidence 必须逐字摘自入库的完整 JD 正文或任职要求，不能引用列表摘要、猜测或简历。"
                + "服务端会重新读取 FULL JD 并执行求职硬条件检查；BLOCKED、非 FULL、原文找不到证据均拒绝。"
                + "UNKNOWN 可以保留但会明确标成待向 HR 核实。失败后最多修正提交一次，未经通过不得输出岗位推荐。";
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
        List<String> issues = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (JsonNode proposal : arguments.get("recommendations")) {
            long id = proposal.get("jobId").asLong();
            String reason = proposal.get("reason").asText().strip();
            String evidence = proposal.get("evidence").asText().strip();
            if (!seen.add(id)) {
                issues.add("jobId=" + id + " 重复提交");
                continue;
            }
            if (reason.isEmpty() || evidence.isEmpty()) {
                issues.add("jobId=" + id + " 缺少推荐理由或证据片段");
                continue;
            }
            JobDescriptionResponse job;
            try {
                // findById checks the authenticated owner. Never accept IDs from another account.
                job = jobs.findById(id);
            } catch (ResourceNotFoundException ex) {
                issues.add("jobId=" + id + " 岗位不存在或无权访问");
                continue;
            }
            if (!"FULL".equals(job.captureDepth())) {
                issues.add("jobId=" + id + " 尚无 FULL JD，请先核实");
                continue;
            }
            String original = (job.description() == null ? "" : job.description()) + "\n"
                    + (job.requirements() == null ? "" : job.requirements());
            if (!original.contains(evidence)) {
                issues.add("jobId=" + id + " 引用片段不在已入库的 JD 原文中");
                continue;
            }
            // Re-check at submission time: conditions and stored JD may have changed across turns.
            var check = JobAvailabilityMatcher.compare(own, job.captureDepth(),
                    job.description(), job.requirements(), job.location());
            if (check.hardBlock()) {
                issues.add("jobId=" + id + " 与已填写的求职硬条件冲突");
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
        // Atomic acceptance: one invalid entry rejects the entire list, not just that entry.
        if (!issues.isEmpty()) throw new BusinessException("RECOMMENDATIONS_REJECTED", String.join("; ", issues));
        return new Result(true, List.copyOf(approved), List.of());
    }

    @Override public Object stepFacts(Object result) {
        Result r = (Result) result;
        return java.util.Map.of("accepted", r.accepted(),
                "recommended", r.recommendations().size(), "issues", r.issues().size());
    }

    public record Submitted(long jobId, String title, String company, String location,
                            String reason, String evidence, List<String> unknownConditions) { }
    public record Result(boolean accepted, List<Submitted> recommendations, List<String> issues) { }
}
