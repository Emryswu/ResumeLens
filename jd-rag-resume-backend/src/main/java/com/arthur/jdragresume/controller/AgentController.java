package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.agent.AgentChatService;
import com.arthur.jdragresume.agent.AgentMessage;
import com.arthur.jdragresume.agent.TranscriptPolicy;
import com.arthur.jdragresume.ai.ReasoningEffort;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentChatService agentChatService;

    public AgentController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    /**
     * The server owns the transcript; the browser submits only one new input. No {@code produces} here: rejected
     * requests must still be able to answer with the usual JSON error body.
     */
    @PostMapping("/chat")
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        TranscriptPolicy.Approval approval = request.approval() == null
                ? null
                : new TranscriptPolicy.Approval(request.approval().toolCallId(), request.approval().approved());
        TranscriptPolicy.BrowserReport browserResult = request.browserResult() == null
                ? null
                : new TranscriptPolicy.BrowserReport(request.browserResult().toolCallId(), request.browserResult().taskId(),
                request.browserResult().outcome(), request.browserResult().report());
        return agentChatService.chat(request.conversationId(), request.question(), approval, browserResult, request.includeTrace(),
                request.reasoningEffort(), request.collectorMode());
    }

    @JsonIgnoreProperties({"messages", "summary"})
    public record ChatRequest(
            @NotNull Long conversationId,
            @Size(max = 4000) String question,
            @Valid ApprovalRequest approval,
            @Valid BrowserResultRequest browserResult,
            boolean includeTrace,
            ReasoningEffort reasoningEffort,
            com.arthur.jdragresume.collect.CollectorMode collectorMode
    ) {
        public ChatRequest {
            reasoningEffort = reasoningEffort == null ? ReasoningEffort.HIGH : reasoningEffort;
            collectorMode = collectorMode == null ? com.arthur.jdragresume.collect.CollectorMode.AUTO : collectorMode;
        }
    }

    /**
     * The page's report on a browser task the previous turn handed out. {@code report} is the
     * executor's final event, required only when {@code outcome} is completed; the collector
     * validates it, since it passed through the user's browser.
     */
    public record BrowserResultRequest(
            @NotBlank @Size(max = AgentMessage.MAX_TOOL_CALL_ID_CHARS) String toolCallId,
            @NotBlank @Size(max = 100) String taskId,
            @NotNull @Pattern(regexp = "completed|cancelled|expired") String outcome,
            JsonNode report
    ) {
    }

    /** {@code approved} is boxed so a missing decision is a 400, not a silent rejection. */
    public record ApprovalRequest(
            @NotBlank @Size(max = AgentMessage.MAX_TOOL_CALL_ID_CHARS) String toolCallId,
            @NotNull Boolean approved
    ) {
    }
}
