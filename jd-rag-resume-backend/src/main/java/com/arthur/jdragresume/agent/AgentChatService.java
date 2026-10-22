package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.security.CurrentUserService;
import com.arthur.jdragresume.security.SlidingWindowRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentChatService {
    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final CurrentUserService currentUserService;
    private final TranscriptPolicy transcriptPolicy;
    private final AgentLoop agentLoop;
    private final SlidingWindowRateLimiter rateLimiter;
    private final TaskExecutor agentTaskExecutor;
    private final AgentProperties properties;
    private final AgentMemoryService memoryService;
    private final AgentToolRegistry toolRegistry;
    private final BrowserTaskRegistry browserTasks;

    public AgentChatService(
            CurrentUserService currentUserService,
            TranscriptPolicy transcriptPolicy,
            AgentLoop agentLoop,
            SlidingWindowRateLimiter rateLimiter,
            @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
            AgentProperties properties,
            AgentMemoryService memoryService,
            AgentToolRegistry toolRegistry,
            BrowserTaskRegistry browserTasks
    ) {
        this.currentUserService = currentUserService;
        this.transcriptPolicy = transcriptPolicy;
        this.agentLoop = agentLoop;
        this.rateLimiter = rateLimiter;
        this.agentTaskExecutor = agentTaskExecutor;
        this.properties = properties;
        this.memoryService = memoryService;
        this.toolRegistry = toolRegistry;
        this.browserTasks = browserTasks;
    }

    /**
     * Everything that can reject the request runs here on the request thread, before the
     * stream opens, so the client gets a plain JSON error with a meaningful status.
     */
    public SseEmitter chat(List<AgentMessage> messages, TranscriptPolicy.Approval approval) {
        return chat(messages, approval, null, false);
    }

    public SseEmitter chat(List<AgentMessage> messages, TranscriptPolicy.Approval approval, String summary, boolean includeTrace) {
        return chat(messages, approval, null, summary, includeTrace);
    }

    /** {@code approval} and {@code browserReport} each answer an open call; at most one may be given. */
    public SseEmitter chat(List<AgentMessage> messages, TranscriptPolicy.Approval approval,
                           TranscriptPolicy.BrowserReport browserReport, String summary, boolean includeTrace) {
        long acceptedAt = System.currentTimeMillis();
        AppUser user = currentUserService.getCurrentUser();
        if (summary != null && summary.length() > properties.getSummaryMaxChars()) {
            throw new BusinessException("AGENT_TRANSCRIPT_TOO_LARGE", "conversation summary is too large");
        }
        if (approval != null && browserReport != null) {
            throw new BusinessException("AGENT_APPROVAL_MISMATCH", "send either an approval or a browser result, not both");
        }
        if (browserReport != null && browserReport.report() != null
                && browserReport.report().toString().length() > properties.getMaxBrowserReportChars()) {
            throw new BusinessException("AGENT_TRANSCRIPT_TOO_LARGE", "browser result is too large");
        }
        String resumed = approval != null ? approval.toolCallId() : browserReport != null ? browserReport.toolCallId() : null;
        TranscriptPolicy.Normalized normalized = transcriptPolicy.normalize(messages, resumed,
                call -> leftBehind(user, call, acceptedAt));
        if (normalized.pending() != null) {
            // An approval cannot run a browser task, and a browser result cannot stand in for an approval.
            boolean browserTask = toolRegistry.find(normalized.pending().name()).orElse(null) instanceof DeferrableTool;
            if (browserTask != (browserReport != null)) {
                throw new BusinessException("AGENT_APPROVAL_MISMATCH", "the pending action expects "
                        + (browserTask ? "a browser result" : "an approval"));
            }
        }
        agentLoop.requireModelReady();
        // Invalid transcripts and a missing model configuration above do not cost quota. A turn rejected below for a full queue
        // does: the limiter has no release, and 20 turns per window leaves room for that.
        if (!rateLimiter.tryAcquire("agent:" + user.getId(), properties.getMaxTurnsPerWindow(),
                properties.getWindowMinutes() * 60_000)) {
            throw new BusinessException("AGENT_RATE_LIMITED", "too many assistant turns, please retry later");
        }

        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeoutMs());
        AtomicBoolean cancelled = new AtomicBoolean();
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));
        AgentToolContext context = new AgentToolContext(user);
        try {
            agentTaskExecutor.execute(() -> runTurn(emitter, cancelled, normalized, approval, browserReport, context,
                    acceptedAt, summary, includeTrace));
        } catch (TaskRejectedException ex) {
            throw new BusinessException("AGENT_BUSY", "assistant is busy, please retry shortly");
        }
        return emitter;
    }

    /**
     * The result for an open call the user walked away from. A browser task is also taken out of
     * the registry, so a report that still arrives for it is refused.
     */
    private String leftBehind(AppUser user, AgentMessage.ToolCall call, long now) {
        return leftBehind(toolRegistry, browserTasks, properties.getBrowserTaskTimeoutSeconds(), user.getId(), call, now);
    }

    static String leftBehind(AgentToolRegistry tools, BrowserTaskRegistry tasks, long timeoutSeconds,
                             long userId, AgentMessage.ToolCall call, long now) {
        if (!(tools.find(call.name()).orElse(null) instanceof DeferrableTool)) {
            return ToolEnvelope.error(call.name(), TranscriptPolicy.DID_NOT_CONFIRM, "用户没有确认就继续提问，此操作未执行");
        }
        return BrowserTaskOutcomes.left(tasks.release(userId, call.id(), call.arguments(), now), call.name(), timeoutSeconds);
    }

    private void runTurn(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            TranscriptPolicy.Normalized normalized,
            TranscriptPolicy.Approval approval,
            TranscriptPolicy.BrowserReport browserReport,
            AgentToolContext context,
            long acceptedAt,
            String summary,
            boolean includeTrace
    ) {
        long queuedMs = System.currentTimeMillis() - acceptedAt;
        AgentEventSink sink = (event, payload) -> {
            if (cancelled.get()) {
                throw new AgentEventSink.ClientGoneException(null);
            }
            try {
                emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                // IOException covers AsyncRequestNotUsableException (client went away);
                // IllegalStateException means the emitter already completed or timed out.
                cancelled.set(true);
                throw new AgentEventSink.ClientGoneException(ex);
            }
        };
        try {
            if (cancelled.get()) return;
            String question = normalized.messages().stream().filter(message -> AgentMessage.USER.equals(message.role()))
                    .reduce((first, second) -> second).orElseThrow().content();
            var memories = memoryService.rememberAndRecall(question, approval == null && browserReport == null);
            // The emitter's timeout has been running since the request was accepted; so does the turn budget.
            AgentLoop.Outcome outcome = agentLoop.run(normalized, approval, context, sink, cancelled::get, acceptedAt,
                    new AgentLoop.TurnOptions(summary, memories, includeTrace, browserReport));
            log.info("agent turn user={} state={} steps={} queuedMs={} elapsedMs={}", context.user().getId(),
                    outcome.state(), outcome.steps(), queuedMs, System.currentTimeMillis() - acceptedAt);
        } catch (AgentEventSink.ClientGoneException ex) {
            log.info("agent turn user={} cancelled: client disconnected", context.user().getId());
        } catch (RuntimeException ex) {
            log.error("agent turn user={} failed", context.user().getId(), ex);
            try {
                sink.emit("error", Map.of("code", "AGENT_INTERNAL_ERROR", "message", "助手内部错误"));
            } catch (AgentEventSink.ClientGoneException ignored) {
                // Nobody is listening any more.
            }
        } finally {
            emitter.complete();
        }
    }
}
