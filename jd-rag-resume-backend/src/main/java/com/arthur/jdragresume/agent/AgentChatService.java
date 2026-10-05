package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.agent.tool.AgentToolContext;
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

    public AgentChatService(
            CurrentUserService currentUserService,
            TranscriptPolicy transcriptPolicy,
            AgentLoop agentLoop,
            SlidingWindowRateLimiter rateLimiter,
            @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
            AgentProperties properties
    ) {
        this.currentUserService = currentUserService;
        this.transcriptPolicy = transcriptPolicy;
        this.agentLoop = agentLoop;
        this.rateLimiter = rateLimiter;
        this.agentTaskExecutor = agentTaskExecutor;
        this.properties = properties;
    }

    /**
     * Everything that can reject the request runs here on the request thread, before the
     * stream opens, so the client gets a plain JSON error with a meaningful status.
     */
    public SseEmitter chat(List<AgentMessage> messages, TranscriptPolicy.Approval approval) {
        AppUser user = currentUserService.getCurrentUser();
        TranscriptPolicy.Normalized normalized = transcriptPolicy.normalize(messages, approval);
        // Invalid transcripts above do not cost quota. A turn rejected below for a full queue
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
            agentTaskExecutor.execute(() -> runTurn(emitter, cancelled, normalized, approval, context));
        } catch (TaskRejectedException ex) {
            throw new BusinessException("AGENT_BUSY", "assistant is busy, please retry shortly");
        }
        return emitter;
    }

    private void runTurn(
            SseEmitter emitter,
            AtomicBoolean cancelled,
            TranscriptPolicy.Normalized normalized,
            TranscriptPolicy.Approval approval,
            AgentToolContext context
    ) {
        long started = System.currentTimeMillis();
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
            AgentLoop.Outcome outcome = agentLoop.run(normalized, approval, context, sink, cancelled::get);
            log.info("agent turn user={} state={} steps={} elapsedMs={}", context.user().getId(),
                    outcome.state(), outcome.steps(), System.currentTimeMillis() - started);
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
