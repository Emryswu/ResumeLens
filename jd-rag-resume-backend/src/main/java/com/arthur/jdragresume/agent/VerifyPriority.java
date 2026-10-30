package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.VerifyBossJobTool;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobAvailabilityMatcher;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import java.util.stream.Stream;

/**
 * Who gets a verification slot. A slot opens a BOSS detail page and is limited per turn, so a job whose stored
 * data already shows a hard conflict with the user's conditions never gets one. UNKNOWN is not a conflict: verifying
 * is how an unknown gets resolved. Ranking and the verify pre-check both decide conflicts through
 * {@link #hardConflict}, and whether a job still needs reading through {@link JdFreshness}.
 */
@Component
public class VerifyPriority {
    public static final String CONFLICT = "VERIFY_HARD_CONFLICT";

    private final JobDescriptionService jobs;
    private final JobSearchPreferencesService preferences;
    private final JdFreshness freshness;

    public VerifyPriority(JobDescriptionService jobs, JobSearchPreferencesService preferences, JdFreshness freshness) {
        this.jobs = jobs;
        this.preferences = preferences;
        this.freshness = freshness;
    }

    /**
     * Why the stored data already rules this job out, or null. Only definite conflicts count: the city of this
     * search, and every BLOCKED finding of {@link JobAvailabilityMatcher} (for a listing, from its BOSS tags).
     */
    public static String hardConflict(JobDescriptionResponse job, JobSearchPreferencesView own, JobSearchContext search) {
        List<String> reasons = new ArrayList<>();
        String jobCity = city(job.location());
        String wanted = search.city();
        if (jobCity != null && wanted != null && !wanted.isBlank() && !"全国".equals(wanted)
                && !jobCity.equals(JobAvailabilityMatcher.compactPlace(wanted))) {
            reasons.add("岗位在" + jobCity + "，本次求职城市是" + wanted);
        }
        var check = JobAvailabilityMatcher.compare(own, job.captureDepth(), job.description(), job.requirements(),
                job.location());
        Stream.of(check.duration(), check.attendance(), check.district(), check.education(), check.experience(),
                        check.graduation())
                .filter(finding -> "BLOCKED".equals(finding.status()))
                .forEach(finding -> reasons.add(finding.explanation()));
        return reasons.isEmpty() ? null : String.join("；", reasons);
    }

    /**
     * The city in a stored location, or null when it cannot be told: {@code 杭州·余杭区·未来科技城} from the
     * extension, {@code 深圳市龙岗区} in older rows. A bare district such as {@code 余杭区} names no city.
     */
    static String city(String location) {
        if (location == null || location.isBlank()) return null;
        String head = location.contains("·") ? location.split("·")[0]
                : location.contains("市") ? location.substring(0, location.indexOf('市')) : null;
        if (head == null) return null;
        String city = JobAvailabilityMatcher.compactPlace(head);
        return city.isEmpty() ? null : city;
    }

    /**
     * The refusal for verifying {@code jobId}, or null when it has no known hard conflict. The refusal names the
     * first of {@code candidates} that would really be verified now; {@code budgetRefusal} is the verification
     * budget's answer for a job id (null when it would run).
     */
    String refusal(long jobId, Long userId, JobSearchContext search, List<Long> candidates,
                   LongFunction<String> budgetRefusal) {
        JobSearchPreferencesView own = preferences.forUser(userId);
        JobDescriptionResponse job = jobs.findById(jobId);
        String conflict = hardConflict(job, own, search);
        if (conflict == null) return null;
        StringBuilder message = new StringBuilder("岗位 ").append(jobId).append("（").append(job.title())
                .append("）与求职硬条件明显冲突：").append(conflict)
                .append("。没有打开详情页，不占核实名额、不扣每日页数、不计步数。");
        JobDescriptionResponse next = null;
        for (Long id : candidates) {
            if (id == jobId) continue;
            JobDescriptionResponse candidate = verifiable(id, own, search, budgetRefusal);
            if (candidate != null) {
                next = candidate;
                break;
            }
        }
        if (next != null) {
            message.append("下一个可核实的候选：jobId=").append(next.id()).append("（").append(next.title())
                    .append("），可调用 verify_boss_job({\"jobId\":").append(next.id()).append("})。");
        } else {
            message.append("本次候选里已没有可核实且无已知硬冲突的岗位：请根据已核实的结果收尾，"
                    + "向用户说明这些岗位与哪些条件冲突；用户愿意放宽条件时再按新条件找。");
        }
        return message.toString();
    }

    private JobDescriptionResponse verifiable(long id, JobSearchPreferencesView own, JobSearchContext search,
                                              LongFunction<String> budgetRefusal) {
        JobDescriptionResponse job;
        try {
            job = jobs.findById(id);
        } catch (ResourceNotFoundException ex) {
            return null;
        }
        if (!freshness.needsVerification(job) || !VerifyBossJobTool.canOpen(job)) return null;
        if (hardConflict(job, own, search) != null) return null;
        return budgetRefusal.apply(id) == null ? job : null;
    }
}
