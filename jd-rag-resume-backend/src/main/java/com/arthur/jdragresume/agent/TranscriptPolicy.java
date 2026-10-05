package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates and normalizes the client-held transcript before any work starts. The client
 * is untrusted, so every structural rule the provider enforces is checked here first:
 * a malformed transcript becomes a 400 for the caller instead of a provider 400 that
 * would surface mid-stream as a 502.
 *
 * <p>Forging is still possible within these rules (a user can hand-craft an assistant
 * message that calls start_analysis and approve it). That grants nothing: the tool runs
 * through the same service, ownership checks and quota as the REST endpoint.
 */
public class TranscriptPolicy {
    static final String DID_NOT_CONFIRM = "USER_DID_NOT_CONFIRM";

    private final AgentProperties properties;

    public TranscriptPolicy(AgentProperties properties) {
        this.properties = properties;
    }

    public Normalized normalize(List<AgentMessage> raw, Approval approval) {
        if (raw == null || raw.isEmpty()) {
            throw invalid("messages must not be empty");
        }
        long rawChars = raw.stream().mapToLong(message -> message == null ? 0 : message.size()).sum();
        if (rawChars > properties.getMaxRequestChars()) {
            throw new BusinessException("AGENT_TRANSCRIPT_TOO_LARGE", "conversation is too large, please start a new one");
        }

        List<AgentMessage> messages = new ArrayList<>();
        Map<String, AgentMessage.ToolCall> open = new LinkedHashMap<>();
        Set<String> seenCallIds = new HashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            AgentMessage message = raw.get(index);
            checkShape(message, index);
            if (index == 0 && !AgentMessage.USER.equals(message.role())) {
                throw invalid("conversation must start with a user message");
            }
            switch (message.role()) {
                case AgentMessage.USER -> {
                    // A new question while a write call awaits approval: the user moved on.
                    // Close the dangling call so the provider sees a complete tool round.
                    open.values().forEach(call -> messages.add(AgentMessage.tool(call.id(),
                            ToolEnvelope.error(call.name(), DID_NOT_CONFIRM, "用户没有确认就继续提问，此操作未执行"))));
                    open.clear();
                    messages.add(message);
                }
                case AgentMessage.ASSISTANT -> {
                    if (!open.isEmpty()) {
                        throw invalid("message " + index + ": previous tool_calls have no results");
                    }
                    if (message.hasToolCalls()) {
                        for (AgentMessage.ToolCall call : message.toolCalls()) {
                            if (!seenCallIds.add(call.id())) {
                                throw invalid("message " + index + ": duplicate tool_call id " + call.id());
                            }
                            open.put(call.id(), call);
                        }
                    }
                    messages.add(message);
                }
                default -> {
                    if (open.remove(message.toolCallId()) == null) {
                        throw invalid("message " + index + ": tool_call_id does not match a pending call of the previous assistant message");
                    }
                    messages.add(message);
                }
            }
        }

        AgentMessage.ToolCall pending = null;
        if (approval == null) {
            if (!AgentMessage.USER.equals(messages.get(messages.size() - 1).role())) {
                if (!open.isEmpty()) {
                    throw new BusinessException("AGENT_CONFIRMATION_PENDING", "an action is waiting for confirmation");
                }
                throw invalid("the last message must be the user's new question");
            }
        } else {
            if (open.size() != 1 || !open.containsKey(approval.toolCallId())) {
                throw new BusinessException("AGENT_APPROVAL_MISMATCH", "approval does not match the pending action");
            }
            pending = open.get(approval.toolCallId());
        }

        List<AgentMessage> compacted = compact(messages);
        return new Normalized(trim(compacted), pending);
    }

    private void checkShape(AgentMessage message, int index) {
        if (message == null || message.role() == null) {
            throw invalid("message " + index + ": role is required");
        }
        switch (message.role()) {
            case AgentMessage.USER -> {
                if (message.content() == null || message.content().isBlank()) {
                    throw invalid("message " + index + ": user content must not be blank");
                }
                if (message.content().length() > properties.getMaxUserMessageChars()) {
                    throw invalid("message " + index + ": user content exceeds " + properties.getMaxUserMessageChars() + " characters");
                }
                if (message.hasToolCalls() || message.toolCallId() != null) {
                    throw invalid("message " + index + ": user messages cannot carry tool fields");
                }
            }
            case AgentMessage.ASSISTANT -> {
                boolean hasText = message.content() != null && !message.content().isBlank();
                if (!hasText && !message.hasToolCalls()) {
                    throw invalid("message " + index + ": assistant message needs content or tool_calls");
                }
                if (message.toolCallId() != null) {
                    throw invalid("message " + index + ": assistant messages cannot carry tool_call_id");
                }
                if (message.hasToolCalls()) {
                    for (AgentMessage.ToolCall call : message.toolCalls()) {
                        if (call == null || call.id() == null || call.id().isBlank()
                                || call.function() == null || call.name() == null || call.name().isBlank()) {
                            throw invalid("message " + index + ": tool_call needs id and function.name");
                        }
                    }
                }
            }
            case AgentMessage.TOOL -> {
                if (message.toolCallId() == null || message.toolCallId().isBlank() || message.content() == null) {
                    throw invalid("message " + index + ": tool message needs tool_call_id and content");
                }
                if (message.hasToolCalls()) {
                    throw invalid("message " + index + ": tool messages cannot carry tool_calls");
                }
            }
            // The system prompt is server-owned; accepting one from the client would let it be replaced.
            default -> throw invalid("message " + index + ": role must be user, assistant or tool");
        }
    }

    /** Earlier turns keep only a short head of each tool result; the current turn stays intact. */
    private List<AgentMessage> compact(List<AgentMessage> messages) {
        int lastUser = lastUserIndex(messages, messages.size());
        int limit = properties.getCompactedToolResultChars();
        List<AgentMessage> result = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            if (index < lastUser && AgentMessage.TOOL.equals(message.role()) && message.content().length() > limit) {
                message = message.withContent(message.content().substring(0, limit) + "…[earlier result compacted]");
            }
            result.add(message);
        }
        return result;
    }

    /** Sliding window: drop the oldest whole turns (user message up to the next one) until within budget. */
    private List<AgentMessage> trim(List<AgentMessage> messages) {
        List<AgentMessage> window = messages;
        while (overBudget(window)) {
            int secondUser = -1;
            for (int index = 1; index < window.size(); index++) {
                if (AgentMessage.USER.equals(window.get(index).role())) {
                    secondUser = index;
                    break;
                }
            }
            if (secondUser < 0) {
                throw new BusinessException("AGENT_TRANSCRIPT_TOO_LARGE", "conversation is too large, please start a new one");
            }
            window = window.subList(secondUser, window.size());
        }
        return List.copyOf(window);
    }

    private boolean overBudget(List<AgentMessage> messages) {
        long chars = messages.stream().mapToLong(AgentMessage::size).sum();
        return messages.size() > properties.getMaxMessages() || chars > properties.getMaxTranscriptChars();
    }

    private static int lastUserIndex(List<AgentMessage> messages, int before) {
        for (int index = before - 1; index >= 0; index--) {
            if (AgentMessage.USER.equals(messages.get(index).role())) {
                return index;
            }
        }
        return -1;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException("AGENT_TRANSCRIPT_INVALID", message);
    }

    public record Approval(String toolCallId, boolean approved) {
    }

    /** {@code pending} is set only when an approval was supplied and matched. */
    public record Normalized(List<AgentMessage> messages, AgentMessage.ToolCall pending) {
    }
}
