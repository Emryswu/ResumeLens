package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.ResumeEvidenceValidator;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * How a refusal made before tool execution affects the current turn and an accepted plan.
 * Tool budgets use the envelope's executed=false independently of these plan/step semantics.
 */
enum PreExecutionRefusal {
    AGENT_STEP_LIMIT(false, true),
    AGENT_PLAN_STEP_SKIPPED(false, true),
    AGENT_PLAN_INVALID(false, false),
    AGENT_PLAN_MISMATCH(false, false),
    AGENT_REPLAN_LIMIT(false, false),
    AGENT_RECOMMENDATIONS_FINISHED(false, false),
    AGENT_CANCELLED(false, false),
    AGENT_TIMEOUT(false, false),
    AGENT_PLAN_INCOMPLETE(false, false),
    ONE_PENDING_ACTION_AT_A_TIME(false, true),
    ONE_CONFIRMATION_AT_A_TIME(false, true),
    JOB_SEARCH_CONDITION_CONFLICT(SearchConditionPolicy.CONFLICT, false, true),
    JOB_SEARCH_REPEAT(false, true),
    JOB_SEARCH_PREREQUISITES(false, false),
    JOB_SEARCH_BUDGET(JobSearchWorkflow.BUDGET_ERROR, false, true),
    BOSS_DIRECTION_REQUIRED(false, true),
    INVALID_ARGUMENTS(false, true),
    INVALID_COMMAND(false, true),
    UNKNOWN_TOOL(false, true),
    NOT_FOUND(false, true),
    TOOL_FAILED(false, false),
    VERIFY_HARD_CONFLICT(VerifyPriority.CONFLICT, false, true),
    RESUME_EVIDENCE_INVALID(ResumeEvidenceValidator.CODE, false, true),
    USER_REJECTED(true, false);

    private final String code;
    private final boolean countsStep;
    private final boolean retrySameStep;

    PreExecutionRefusal(boolean countsStep, boolean retrySameStep) {
        this(null, countsStep, retrySameStep);
    }

    PreExecutionRefusal(String code, boolean countsStep, boolean retrySameStep) {
        this.code = code == null ? name() : code;
        this.countsStep = countsStep;
        this.retrySameStep = retrySameStep;
    }

    String code() {
        return code;
    }

    boolean countsStep() {
        return countsStep;
    }

    boolean retrySameStep() {
        return retrySameStep;
    }

    private static final Map<String, PreExecutionRefusal> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(PreExecutionRefusal::code, Function.identity()));

    static final Set<String> NOT_STEPPED = Arrays.stream(values())
            .filter(value -> !value.countsStep)
            .map(PreExecutionRefusal::code)
            .collect(Collectors.toUnmodifiableSet());

    static final Set<String> RETRY_SAME_STEP = Arrays.stream(values())
            .filter(PreExecutionRefusal::retrySameStep)
            .map(PreExecutionRefusal::code)
            .collect(Collectors.toUnmodifiableSet());

    static boolean registered(String code) {
        return BY_CODE.containsKey(code);
    }
}
