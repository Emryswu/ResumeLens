package com.arthur.jdragresume.agent;

import java.util.Optional;

/**
 * Decides whether search conditions the model is about to use loosen a hard condition from a higher layer.
 *
 * <p>Layers, highest first: what the user approved on a confirmation card, the user's saved profile, conditions
 * already stored in the conversation, and the model's own arguments. The model may freely tighten conditions, fill
 * unset ones and change the keyword; it may not loosen a profile rule. Today the only profile rule is that a known
 * student looking for an internship searches BOSS with experience 在校生. Loosening it takes the user's approval via
 * {@code relax_job_search_condition}; nothing parsed from chat text or written into tool arguments counts.
 *
 * <p>This only governs the BOSS search filter. The user's student status itself is never changed here, and every
 * recommendation is still checked against the full JD by the recommendation flow.
 */
public final class SearchConditionPolicy {
    public static final String CONFLICT = "JOB_SEARCH_CONDITION_CONFLICT";
    public static final String STUDENT_EXPERIENCE = "在校生";

    private SearchConditionPolicy() {
    }

    /** The refusal message for the model, or empty when {@code next} may be used as is. */
    public static Optional<String> conflict(JobSearchContext next, Boolean currentlyStudent) {
        if (!Boolean.TRUE.equals(currentlyStudent) || !"实习".equals(next.jobType())) return Optional.empty();
        if (next.userConfirmed().contains("experience")) return Optional.empty();
        String experience = next.effectiveExperience();
        if (STUDENT_EXPERIENCE.equals(experience)) return Optional.empty();
        return Optional.of("条件冲突：字段 experience 传了 " + experience + "（来源：模型参数），但用户在求职条件里填写的是在校生，"
                + "找实习时只允许 experience=" + STUDENT_EXPERIENCE + "（来源：用户求职条件）。"
                + "去掉 experience 或传 " + STUDENT_EXPERIENCE + " 后重试；只有用户明确想放宽时才调用 "
                + "relax_job_search_condition（传 jobType=实习 和放宽后的 experience）请用户确认，不要自行放宽。"
                + "本次未执行，不计入搜索预算。");
    }

    /**
     * Why asking the user to loosen {@code next} would be pointless, for a relaxation request that has no conflict.
     * Each reason names a step that works from this state, so the model is never sent back and forth.
     */
    public static String relaxationNotNeeded(JobSearchContext next, Boolean currentlyStudent) {
        if (!Boolean.TRUE.equals(currentlyStudent)) {
            return "用户的求职条件里没有填写在校生，经验筛选不受限制，直接在 collect_boss_jobs 里传 experience 即可，无需确认";
        }
        if (!"实习".equals(next.jobType())) {
            return "本会话的岗位类型是 " + (next.jobType() == null ? "未指定" : next.jobType())
                    + "，只有找实习时经验筛选才受限。如果用户是在找实习并想放宽，请带上 jobType=实习 重新调用本工具";
        }
        return "经验筛选 " + next.effectiveExperience() + " 不比 " + STUDENT_EXPERIENCE + " 宽，或用户已同意过，"
                + "直接在 collect_boss_jobs 里传即可，无需确认";
    }
}
