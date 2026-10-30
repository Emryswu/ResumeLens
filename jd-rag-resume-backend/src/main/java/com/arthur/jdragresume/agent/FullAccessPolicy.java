package com.arthur.jdragresume.agent;

import java.util.Set;

/**
 * The only list of actions that 完全访问 lets start without the user's click. Everything else, including any
 * tool added later, keeps its card: a tool is skippable only after it is added here by name.
 *
 * <p>Only read-only browser work belongs here, and only work already governed by the BOSS budget
 * ({@link JobSearchWorkflow#BOSS_TOOLS}): skipping the click must never skip the daily pages, the per-call page
 * cap or the random waits. Tools whose confirmation is the user's authorization ({@code requiresConfirmation},
 * such as relax_job_search_condition) or that have side effects (contacting a recruiter, sending a message)
 * must never be listed; {@code FullAccessPolicyTests} enforces both.
 */
public final class FullAccessPolicy {
    static final Set<String> SKIPS_USER_START = Set.of(JobSearchWorkflow.TOOL, JobSearchWorkflow.VERIFY_TOOL);

    private FullAccessPolicy() {
    }

    /** Whether the page may start this browser task as soon as it is issued. */
    static boolean autoStart(boolean fullAccess, String toolName) {
        return fullAccess && SKIPS_USER_START.contains(toolName);
    }
}
