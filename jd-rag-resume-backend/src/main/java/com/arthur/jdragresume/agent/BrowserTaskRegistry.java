package com.arthur.jdragresume.agent;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Browser tasks the assistant handed out and is waiting on. The transcript lives in the
 * browser, so the deadline cannot: the server records when each task was issued and is the
 * only judge of whether a report came back in time.
 *
 * <p>One task per user (the extension runs one collection at a time anyway); issuing a new one
 * replaces the old, whose report is then refused as unknown. In memory, like the rate limiter:
 * after a restart every outstanding task is unknown, which the model reports as such.
 */
public class BrowserTaskRegistry {
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
