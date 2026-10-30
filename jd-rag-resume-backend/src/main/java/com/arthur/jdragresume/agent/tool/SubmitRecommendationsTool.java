package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.ConversationStore;
import com.arthur.jdragresume.agent.JdFreshness;
import com.arthur.jdragresume.agent.JobSearchContext;
import com.arthur.jdragresume.agent.VerifyPriority;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobAvailabilityMatcher;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.ResumeService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ResumeService resumes;
    private final JdFreshness freshness;
    private final ConversationStore conversations;

    @Autowired
    public SubmitRecommendationsTool(JobDescriptionService jobs, JobSearchPreferencesService preferences, ResumeService resumes,
                                     JdFreshness freshness, ConversationStore conversations) {
        this.jobs = jobs;
        this.preferences = preferences;
        this.resumes = resumes;
        this.freshness = freshness;
        this.conversations = conversations;
    }

    @Override public String name() { return "submit_recommendations"; }

    @Override public String description() {
        return "正式推荐岗位前必须调用此工具提交 recommendations 数组，传当前简历 resumeId，每项包含 jobId、reason、evidence 和至少 1 条 resumeEvidence：{quote, supports}。"
                + "evidence 至少 8 个字符，来自 FULL JD 正文或任职要求（允许空白格式不同），不能只引用职位标题。"
                + "quote 必须逐字摘自 get_resume 的简历原文（仅忽略空白与全半角标点），去空白后 "
                + ResumeEvidenceValidator.MIN_QUOTE_CHARS + "～" + ResumeEvidenceValidator.MAX_QUOTE_CHARS
                + " 字符；supports 写对应的 JD 要求。引用被拒时调用 get_resume 重读同一简历，修正后重提被拒条目。"
                + "服务端逐条检查完整 JD 是否仍在核实时效内、实际证据和当前求职硬条件：通过的保留，不通过的附具体原因；"
                + "JD 核实已过期的按提示重新 verify_boss_job 后再重提。"
                + "JD 未提及学历/经验或明确不限时计入合格并标注；优先条件不满足只提示。"
                + "学历/毕业日期等用户资料缺失才保持 UNKNOWN，提示到求职条件页填写；最多修正一次。";
    }

    @Override public ToolSchema parameters() {
        return ToolSchema.object().integer("resumeId", "当前选定简历 id，来自 get_resume", true, 1, Long.MAX_VALUE).objectArray("recommendations", "准备正式推荐的岗位和 JD 证据",
                true, 5, ToolSchema.object()
                        .integer("jobId", "入库岗位 id", true, 1, Long.MAX_VALUE)
                        .string("reason", "为什么推荐该岗位（中文）", true, 240)
                        .string("evidence", "从 FULL JD 原文逐字复制的证据片段", true, 300)
                        .objectArray("resumeEvidence", "至少 1 条简历原文及对应 JD 要求", false, 3, ToolSchema.object()
                                .string("quote", "简历原文片段，长度按归一化后检查", true, 1000)
                                .string("supports", "这条引用对应的 JD 要求", true, 300)));
    }

    @Override public Object execute(JsonNode arguments, AgentToolContext context) {
        long resumeId = arguments.get("resumeId").asLong();
        // ResumeService checks ownership and supplies the full stored text, not the get_resume preview.
        String storedText = resumes.findById(resumeId).rawText();
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
            String citationProblem = ResumeEvidenceValidator.validate(proposal.path("resumeEvidence"), storedText);
            if (citationProblem != null) {
                rejected.add(new Rejected(id, label, label + "（jobId=" + id + "）" + citationProblem
                        + "；下一步调用 get_resume({\"resumeId\":" + resumeId
                        + "}) 重读原文，再仅重提被拒推荐。", true));
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
            if (freshness.expired(job)) {
                // Re-verifying is pointless when the stored data already conflicts; verify_boss_job would refuse it.
                String conflict = VerifyPriority.hardConflict(job, own, search(context));
                rejected.add(new Rejected(id, label, conflict != null
                        ? "与求职硬条件冲突：" + conflict + "（JD 核实已过期，无需重新核实）"
                        : "JD 核实已过期（超过 " + freshness.maxAge().toDays() + " 天），下一步调用 verify_boss_job({\"jobId\":"
                                + id + "}) 重新核实，再重提这条"));
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
            if ("UNKNOWN".equals(check.education().status())) unknown.add("学历");
            if ("UNKNOWN".equals(check.experience().status())) unknown.add("工作经验");
            if ("UNKNOWN".equals(check.graduation().status())) unknown.add("毕业时间");
            if ("UNKNOWN".equals(check.startDate().status())) unknown.add("最早到岗日期需与 HR 确认");
            if (!own.configured()) unknown.add("用户尚未配置求职条件");
            approved.add(new Submitted(id, job.title(), job.companyName(), job.location(),
                    reason, evidence, resumeId, resumeEvidence(proposal), List.copyOf(unknown), check.preferredWarnings(),
                    check.jdNotes(), check.missingUserFields()));
        }
        return new Result(rejected.isEmpty(), List.copyOf(approved), List.copyOf(rejected));
    }

    private JobSearchContext search(AgentToolContext context) {
        return conversations == null || context.run() == null
                ? JobSearchContext.empty() : conversations.searchContext(context.run());
    }

    private static List<ResumeEvidence> resumeEvidence(JsonNode proposal) {
        List<ResumeEvidence> evidence = new ArrayList<>();
        for (JsonNode item : proposal.get("resumeEvidence")) {
            evidence.add(new ResumeEvidence(item.get("quote").asText(), item.get("supports").asText()));
        }
        return List.copyOf(evidence);
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ");
    }

    @Override public int resultMaxChars(int configuredLimit) {
        // Five valid proposals can exceed the default 4k response ceiling; the loop finishes immediately on acceptance.
        return Math.max(configuredLimit, 30_000);
    }

    @Override public Object stepFacts(Object result) {
        Result r = (Result) result;
        return java.util.Map.of("accepted", r.accepted(),
                "recommended", r.recommendations().size(), "rejected", r.rejected().size(),
                "recommendations", r.recommendations());
    }

    public record Submitted(long jobId, String title, String company, String location,
                            String reason, String evidence, long resumeId, List<ResumeEvidence> resumeEvidence, List<String> unknownConditions,
                            List<String> preferredWarnings, List<String> jdNotes,
                            List<String> missingUserFields) { }
    public record ResumeEvidence(String quote, String supports) { }
    public record Rejected(long jobId, String title, String reason, boolean notExecuted) {
        public Rejected(long jobId, String title, String reason) { this(jobId, title, reason, false); }
    }
    public record Result(boolean accepted, List<Submitted> recommendations, List<Rejected> rejected) { }
}
