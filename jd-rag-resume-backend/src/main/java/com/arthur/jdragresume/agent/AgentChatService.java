package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.common.LogSafe;
import com.arthur.jdragresume.agent.tool.AgentToolContext;
import com.arthur.jdragresume.agent.tool.AgentToolRegistry;
import com.arthur.jdragresume.agent.tool.DeferrableTool;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.ai.ReasoningEffort;
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
    private final ConversationStore conversations;

    public AgentChatService(
            CurrentUserService currentUserService,
            TranscriptPolicy transcriptPolicy,
            AgentLoop agentLoop,
            SlidingWindowRateLimiter rateLimiter,
            @Qualifier("agentTaskExecutor") TaskExecutor agentTaskExecutor,
            AgentProperties properties,
            AgentMemoryService memoryService,
            AgentToolRegistry toolRegistry,
            BrowserTaskRegistry browserTasks,
            ConversationStore conversations
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
        this.conversations = conversations;
    }

    /** Ownership, lease acquisition and request validation finish before opening SSE. */
    public SseEmitter chat(long conversationId, String question, TranscriptPolicy.Approval approval,
                           TranscriptPolicy.BrowserReport browserReport, boolean includeTrace,
                           ReasoningEffort reasoningEffort, com.arthur.jdragresume.collect.CollectorMode collectorMode) {
        AppUser user = currentUserService.getCurrentUser();
        // Resolve ownership before model/quota checks, including for writes and pending-action reports.
        conversations.detail(user.getId(), conversationId, 0);
        int inputs = (question != null ? 1 : 0) + (approval != null ? 1 : 0) + (browserReport != null ? 1 : 0);
        if (inputs != 1 || question != null && question.isBlank()) {
            throw new BusinessException("AGENT_INVALID_INPUT", "send exactly one question, approval or browser result");
        }
        if (browserReport != null && browserReport.report() != null
                && browserReport.report().toString().length() > properties.getMaxBrowserReportChars()) {
            throw new BusinessException("AGENT_TRANSCRIPT_TOO_LARGE", "browser result is too large");
        }
        agentLoop.requireModelReady();
        if (!rateLimiter.tryAcquire("agent:" + user.getId(), properties.getMaxTurnsPerWindow(),
                properties.getWindowMinutes() * 60_000)) {
            throw new BusinessException("AGENT_RATE_LIMITED", "too many assistant turns, please retry later");
        }
        String resumed = approval != null ? approval.toolCallId() : browserReport != null ? browserReport.toolCallId() : null;
        var run = conversations.accept(user.getId(), conversationId, question, approval, browserReport,
                properties.getTurnTimeoutSeconds() * 1000,
                messages -> transcriptPolicy.normalize(messages, resumed, call -> ToolEnvelope.error(call.name(), TranscriptPolicy.DID_NOT_CONFIRM, "操作未确认")));
        SseEmitter emitter = new SseEmitter(properties.getEmitterTimeoutMs());
        AtomicBoolean disconnected = new AtomicBoolean();
        emitter.onCompletion(() -> disconnected.set(true));
        emitter.onTimeout(() -> disconnected.set(true));
        emitter.onError(error -> disconnected.set(true));
        try {
            agentTaskExecutor.execute(() -> runTurn(emitter, disconnected, run, approval, browserReport,
                    new AgentToolContext(user, run, collectorMode), includeTrace, reasoningEffort));
        } catch (TaskRejectedException ex) {
            conversations.fail(run, "AGENT_BUSY", "助手队列已满，本轮未执行");
            throw new BusinessException("AGENT_BUSY", "assistant is busy, please retry shortly");
        }
        return emitter;
    }

    static String leftBehind(AgentToolRegistry tools, BrowserTaskRegistry tasks, long timeoutSeconds,
                             long userId, AgentMessage.ToolCall call, long now) {
        if (!(tools.find(call.name()).orElse(null) instanceof DeferrableTool)) {
            return ToolEnvelope.error(call.name(), TranscriptPolicy.DID_NOT_CONFIRM, "用户没有确认就继续提问，此操作未执行");
        }
        return BrowserTaskOutcomes.left(tasks.release(userId, call.id(), call.arguments(), now), call.name(), timeoutSeconds);
    }

    private void runTurn(SseEmitter emitter, AtomicBoolean disconnected, ConversationStore.Run run,
                         TranscriptPolicy.Approval approval, TranscriptPolicy.BrowserReport browserReport,
                         AgentToolContext context, boolean includeTrace, ReasoningEffort reasoningEffort) {
        AgentEventSink sink = new AgentEventSink() {
            @Override public void checkpoint(List<AgentMessage> messages, String summary) {
                conversations.checkpoint(run, messages, summary);
            }
            @Override public void emit(String event, Object payload) {
                if (payload instanceof AgentLoop.DoneEvent done) {
                    Map<String, Object> terminal = new java.util.LinkedHashMap<>();
                    terminal.put("state", done.state());
                    terminal.put("steps", done.steps());
                    if (done.trace() != null) terminal.put("trace", done.trace());
                    payload = terminal;
                }
                var saved = conversations.emit(run, event, payload);
                send(emitter, disconnected, saved);
            }
        };
        try {
            // The accepted input is an event too, so live and restored turns have identical inputs.
            var events = conversations.detail(run.userId(), run.conversationId(), 0).events();
            send(emitter, disconnected, events.getLast());
            String question = run.input().messages().stream().filter(message -> AgentMessage.USER.equals(message.role()))
                    .reduce((first, second) -> second).orElseThrow().content();
            var memories = memoryService.rememberAndRecall(question, approval == null && browserReport == null);
            agentLoop.run(run.input(), approval, context, sink, () -> !conversations.isCurrent(run), run.acceptedAt(),
                    new AgentLoop.TurnOptions(run.summary(), memories, includeTrace, browserReport, reasoningEffort));
        } catch (AgentEventSink.ClientGoneException ex) {
            log.info("agent run {} no longer owns its lease", run.runId());
        } catch (RuntimeException ex) {
            log.error("agent run {} failed {}", run.runId(), LogSafe.describe(ex));
            conversations.fail(run, "AGENT_INTERNAL_ERROR", "助手执行失败，可以继续提问");
        } finally {
            conversations.fail(run, "AGENT_INTERRUPTED", "助手执行已中断，可以继续提问");
            emitter.complete();
        }
    }

    /** A failed delivery detaches this subscriber; it never cancels the durable run. */
    private static void send(SseEmitter emitter, AtomicBoolean disconnected, ConversationStore.Event event) {
        if (disconnected.get()) return;
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type())
                    .data(event.data(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            disconnected.set(true);
        }
    }
}
