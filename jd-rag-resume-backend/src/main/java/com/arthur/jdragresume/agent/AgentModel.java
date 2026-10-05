package com.arthur.jdragresume.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

/**
 * One reasoning step: given the conversation so far, either answer or request tool calls.
 * The loop owns the system prompt, the step budget and tool execution; a model only decides.
 */
public interface AgentModel {
    Reply next(String systemPrompt, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout);

    /** Throws when the model cannot be called at all, so the request is refused before a stream opens. */
    default void requireReady() {
    }

    record ToolDefinition(String name, String description, JsonNode parameters) {
    }

    record Reply(String content, List<AgentMessage.ToolCall> toolCalls) {
        public Reply {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Reply answer(String content) {
            return new Reply(content, List.of());
        }

        public static Reply call(AgentMessage.ToolCall... calls) {
            return new Reply(null, List.of(calls));
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }
}
