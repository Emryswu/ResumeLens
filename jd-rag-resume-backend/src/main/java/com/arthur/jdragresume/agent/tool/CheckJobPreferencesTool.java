package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.service.JobAvailabilityMatcher;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * One owned JD + owned profile; no cross-account reads and no guessed hard conditions.
 */
@Component
public class CheckJobPreferencesTool implements AgentTool {
    private final JobDescriptionService jobs;
    private final JobSearchPreferencesService preferences;

    public CheckJobPreferencesTool(JobDescriptionService jobs, JobSearchPreferencesService preferences) {
        this.jobs = jobs;
        this.preferences = preferences;
    }

    @Override public String name() { return "check_job_preferences"; }
    @Override public String description() {
        return "把用户真实填写的到岗时间、实习月数、每周天数、地区偏好与单个职位的 FULL JD 对照。"
                + "BLOCKED 是明确冲突，UNKNOWN 表示缺少可靠信息，LOWER_PRIORITY 只是地理偏好不合。"
                + "列表摘要不能当作核实完成；模型不得把 UNKNOWN 当 MEETS，也不得把偏好当硬性淘汰条件。";
    }
    @Override public ToolSchema parameters() {
        return ToolSchema.object().integer("jobId", "当前用户的职位 id", true, 1, Long.MAX_VALUE);
    }
    @Override public Object execute(JsonNode arguments, AgentToolContext context) {
        JobDescriptionResponse job = jobs.findById(arguments.get("jobId").asLong());
        var own = preferences.forUser(context.user().getId());
        return new Result(job.id(), job.captureDepth(), own.configured(),
                JobAvailabilityMatcher.compare(own, job.captureDepth(), job.description(),
                        job.requirements(), job.location()),
                "仅为显式 JD 字句的保守检查；含糊条件、最早到岗要求仍需向 HR 核实");
    }

    record Result(Long jobId, String captureDepth, boolean preferencesConfigured,
                  JobAvailabilityMatcher.Comparison findings, String caveat) { }
}
