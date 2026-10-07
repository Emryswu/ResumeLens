package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One transcript entry in OpenAI chat-completions shape. The browser keeps the transcript
 * and sends it back every turn, so this record is both the wire format and the model input.
 * The system prompt is never part of it: the server injects that on every call.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    public static final String TOOL = "tool";

    public AgentMessage {
        toolCalls = toolCalls == null || toolCalls.isEmpty() ? null : List.copyOf(toolCalls);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(USER, content, null, null);
    }

    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        return new AgentMessage(ASSISTANT, content, toolCalls, null);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage(TOOL, content, null, toolCallId);
    }

    @JsonIgnore
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public AgentMessage withContent(String newContent) {
        return new AgentMessage(role, newContent, toolCalls, toolCallId);
    }

    /** Characters counted against the transcript budget. */
    @JsonIgnore
    public int size() {
        int total = content == null ? 0 : content.length();
        if (toolCalls != null) {
            for (ToolCall call : toolCalls) {
                total += call.function().name().length() + call.function().arguments().length();
            }
        }
        return total;
    }

    /** Provider payload; built by hand so null fields are omitted regardless of ObjectMapper settings. */
    public Map<String, Object> toProviderMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
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
