package com.arthur.jdragresume.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Browser tasks are durably bound to a conversation and consumed once, using the original deadline.
 *
 * The memory-only overloads support isolated loop tests with synthetic clocks.
 */
public class BrowserTaskRegistry {
    private final ConversationStore store;

    /** Isolated loop tests use memory; the application always injects the durable store. */
    public BrowserTaskRegistry() { this.store = null; }
    public BrowserTaskRegistry(ConversationStore store) { this.store = store; }

    public Task issue(com.arthur.jdragresume.agent.tool.AgentToolContext context, String callId, String tool,
                      String arguments, long now, long timeoutMs) {
        return store == null ? issue(context.user().getId(), callId, tool, arguments, now, timeoutMs)
                : store.issueBrowser(context.run(), callId, tool, arguments, now, timeoutMs);
    }

    public Claim claim(com.arthur.jdragresume.agent.tool.AgentToolContext context, String taskId, String callId,
                       String arguments, long now) {
        return store == null ? claim(context.user().getId(), taskId, callId, arguments, now)
                : store.claimBrowser(context.run(), taskId, callId, arguments, now);
    }
    /** Tasks nobody came back for are dropped once they are this far past their deadline. */
    private static final long RETENTION_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_ENTRIES = 10_000;

    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();

    public Task issue(long userId, String toolCallId, String tool, String arguments, long now, long timeoutMs) {
        if (tasks.size() >= MAX_ENTRIES) {
            tasks.values().removeIf(task -> now - task.expiresAt() > RETENTION_MS);
        }
        Task task = new Task("bt_" + UUID.randomUUID().toString().replace("-", ""),
                toolCallId, tool, arguments, now, now + timeoutMs);
        tasks.put(userId, task);
        return task;
    }

    /**
     * Takes the task a report claims to answer. It must be the user's current task, for the same
     * call with the same arguments; anything else is unknown and leaves the current task alone.
     */
    public Claim claim(long userId, String taskId, String toolCallId, String arguments, long now) {
        return take(userId, now, task -> task.taskId().equals(taskId) && task.matches(toolCallId, arguments));
    }

    /** Takes the task behind an open call the user walked away from (they asked something new instead). */
    public Claim release(long userId, String toolCallId, String arguments, long now) {
        return take(userId, now, task -> task.matches(toolCallId, arguments));
    }

    private Claim take(long userId, long now, java.util.function.Predicate<Task> matches) {
        AtomicReference<Task> taken = new AtomicReference<>();
        tasks.computeIfPresent(userId, (ignored, task) -> {
            if (!matches.test(task)) {
                return task;
            }
            taken.set(task);
            return null;
        });
        Task task = taken.get();
        if (task == null) {
            return new Claim(ClaimState.UNKNOWN, null);
        }
        return new Claim(now > task.expiresAt() ? ClaimState.EXPIRED : ClaimState.ACTIVE, task);
    }

    public record Task(String taskId, String toolCallId, String tool, String arguments, long issuedAt, long expiresAt) {
        boolean matches(String callId, String callArguments) {
            return toolCallId.equals(callId) && Objects.equals(arguments, callArguments);
        }
    }

    public record Claim(ClaimState state, Task task) {
    }

    public enum ClaimState {
        /** Issued, not yet answered, still within its deadline. */
        ACTIVE,
        /** Issued, but the deadline passed before anyone answered. */
        EXPIRED,
        /** No such task: never issued, already answered, replaced, or lost in a restart. */
        UNKNOWN
    }
}
