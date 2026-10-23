package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One server-owned transcript entry in OpenAI chat-completions shape, stored between turns.
 * The system prompt is never part of it: the server injects that on every call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId,
        @JsonProperty("reasoning_content") String reasoningContent
) {
    public AgentMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, null);
    }

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";
    /** Bounds externally supplied pairing ids; generated call_ UUIDs and scripted_ ids fit within this limit. */
    public static final int MAX_TOOL_CALL_ID_CHARS = 64;

    public AgentMessage {
        toolCalls = toolCalls == null || toolCalls.isEmpty() ? null : List.copyOf(toolCalls);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(USER, content, null, null);
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        return new AgentMessage(ASSISTANT, content, toolCalls, null);
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls, String reasoningContent) {
        return new AgentMessage(ASSISTANT, content, toolCalls, null, reasoningContent);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage(TOOL, content, null, toolCallId);
    }

    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public AgentMessage withContent(String newContent) {
        return new AgentMessage(role, newContent, toolCalls, toolCallId, reasoningContent);
    }

    /** Characters counted against the transcript budget. */
    @JsonIgnore
    public long size() {
        long total = (long) role.length() + (content == null ? 0 : content.length())
                + (toolCallId == null ? 0 : toolCallId.length())
                + (reasoningContent == null ? 0 : reasoningContent.length());
        if (toolCalls != null) {
            for (ToolCall call : toolCalls) {
                total += (long) call.id().length() + call.type().length()
                        + call.function().name().length() + call.function().arguments().length();
            }
        }
        return total;
    }

    /** Provider payload; built by hand so null fields are omitted regardless of ObjectMapper settings. */
    public Map<String, Object> toProviderMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        if (ASSISTANT.equals(role)) {
            // Non-thinking and older replies have no reasoning. The thinking tool API still needs this field.
            message.put("reasoning_content", reasoningContent == null ? "" : reasoningContent);
        }
        if (hasToolCalls()) {
            message.put("tool_calls", toolCalls.stream().map(ToolCall::toProviderMap).toList());
        }
        if (toolCallId != null) {
            message.put("tool_call_id", toolCallId);
        }
        return message;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolCall(String id, String type, ToolFunction function) {
        public ToolCall {
            type = type == null || type.isBlank() ? "function" : type;
        }

        public static ToolCall of(String id, String name, String arguments) {
            return new ToolCall(id, "function", new ToolFunction(name, arguments));
        }

        @JsonIgnore
        public String name() {
            return function.name();
        }

        @JsonIgnore
        public String arguments() {
            return function.arguments();
        }

        Map<String, Object> toProviderMap() {
            return Map.of(
                    "id", id,
                    "type", type,
                    "function", Map.of("name", function.name(), "arguments", function.arguments())
            );
        }
    }

    public record ToolFunction(String name, String arguments) {
        public ToolFunction {
            arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        }
    }
}
