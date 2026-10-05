package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.ai.AiClient;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Function calling over the OpenAI-compatible chat-completions endpoint already used for analysis. */
public class OpenAiCompatibleAgentModel implements AgentModel {
    private final AiClient aiClient;

    public OpenAiCompatibleAgentModel(AiClient aiClient) {
        this.aiClient = aiClient;
    }

    @Override
    public Reply next(String systemPrompt, List<AgentMessage> transcript, List<ToolDefinition> tools, Duration timeout) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        transcript.forEach(message -> messages.add(message.toProviderMessage()));
        List<Map<String, Object>> toolPayload = tools.stream()
                .map(tool -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.name(),
                                "description", tool.description(),
                                "parameters", tool.parameters()
                        )
                ))
                .toList();

        JsonNode message = aiClient.chatWithTools(messages, toolPayload, timeout);
        JsonNode contentNode = message.path("content");
        String content = contentNode.isTextual() && !contentNode.asText().isBlank() ? contentNode.asText() : null;

        List<AgentMessage.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String name = function.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode arguments = function.path("arguments");
            // The spec says a JSON-encoded string; some compatible providers send an object.
            String argumentsJson = arguments.isTextual() ? arguments.asText() : arguments.isMissingNode() ? "{}" : arguments.toString();
            String id = call.path("id").asText("");
            calls.add(AgentMessage.ToolCall.of(id.isBlank() ? "call_" + UUID.randomUUID() : id, name, argumentsJson));
        }
        if (content == null && calls.isEmpty()) {
            throw new BusinessException("AI_RESPONSE_EMPTY", "AI response has neither content nor tool calls");
        }
        return new Reply(content, calls);
    }
}
