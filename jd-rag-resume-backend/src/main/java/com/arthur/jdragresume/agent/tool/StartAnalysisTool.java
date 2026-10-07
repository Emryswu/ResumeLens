package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.analysis.AiAnalysisRequest;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.service.AiAnalysisService;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * The only tool with side effects: it spends the user's analysis quota and an LLM call.
 * The loop never runs it without an explicit approval of this exact call. Approval is not
 * authorization, though: {@link AiAnalysisService#submit} still enforces ownership, quota
 * and rate limits exactly as for the REST endpoint.
 */
@Component
public class StartAnalysisTool implements AgentTool {
    private final AiAnalysisService aiAnalysisService;
    private final ResumeService resumeService;
    private final JobDescriptionService jobDescriptionService;

    public StartAnalysisTool(
            AiAnalysisService aiAnalysisService,
            ResumeService resumeService,
            JobDescriptionService jobDescriptionService
    ) {
        this.aiAnalysisService = aiAnalysisService;
        this.resumeService = resumeService;
        this.jobDescriptionService = jobDescriptionService;
    }

    @Override
    public String name() {
        return "start_analysis";
    }

    @Override
    public String description() {
        return "为一份简历和一个职位发起完整的 AI 匹配分析（异步，生成匹配分、证据链和面试问题）。"
                + "会消耗用户的分析配额，系统会先请用户确认。发起后状态为 PENDING，界面会自动跟进结果，"
                + "你只需告诉用户已发起，不要轮询。仅在用户明确想要完整分析时调用。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .integer("resumeId", "简历 id", true, 1, Long.MAX_VALUE)
                .integer("jobId", "职位 id", true, 1, Long.MAX_VALUE);
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public Object preview(JsonNode arguments, AgentToolContext context) {
        ResumeResponse resume = resumeService.findById(arguments.get("resumeId").asLong());
        JobDescriptionResponse job = jobDescriptionService.findById(arguments.get("jobId").asLong());
        return new Preview(resume.id(), resume.title(), job.id(), job.title(), job.companyName());
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        AiAnalysisService.Submission submission = aiAnalysisService.submit(new AiAnalysisRequest(
                arguments.get("resumeId").asLong(),
                arguments.get("jobId").asLong()
        ));
        return new Started(
                submission.analysis().id(),
                submission.analysis().resumeId(),
                submission.analysis().jobDescriptionId(),
                submission.analysis().status().name(),
                submission.reusedPending()
        );
    }

    record Preview(Long resumeId, String resumeTitle, Long jobId, String jobTitle, String companyName) {
    }

    record Started(Long analysisId, Long resumeId, Long jobId, String status, boolean reusedPending) {
    }
}
