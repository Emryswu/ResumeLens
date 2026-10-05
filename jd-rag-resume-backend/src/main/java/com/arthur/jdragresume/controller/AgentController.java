package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.agent.AgentChatService;
import com.arthur.jdragresume.agent.AgentMessage;
import com.arthur.jdragresume.agent.TranscriptPolicy;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentChatService agentChatService;

    public AgentController(AgentChatService agentChatService) {
        this.agentChatService = agentChatService;
    }

    /**
     * Stateless: the browser sends the whole transcript every turn and gets the updated one
     * back in the final {@code done} event. No {@code produces} here on purpose: rejected
     * requests must still be able to answer with the usual JSON error body.
     */
    @PostMapping("/chat")
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        TranscriptPolicy.Approval approval = request.approval() == null
                ? null
                : new TranscriptPolicy.Approval(request.approval().toolCallId(), request.approval().approved());
        return agentChatService.chat(request.messages(), approval);
    }

    public record ChatRequest(
            @NotEmpty @Size(max = 400) List<AgentMessage> messages,
            @Valid ApprovalRequest approval
    ) {
    }

    /** {@code approved} is boxed so a missing decision is a 400, not a silent rejection. */
    public record ApprovalRequest(@NotBlank String toolCallId, @NotNull Boolean approved) {
    }
}
