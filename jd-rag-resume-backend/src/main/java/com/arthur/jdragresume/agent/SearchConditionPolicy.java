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
    static final String STUDENT_EXPERIENCE = "在校生";

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
                + "relax_job_search_condition 请用户确认，不要自行放宽。本次未执行，不计入搜索预算。");
    }
}
