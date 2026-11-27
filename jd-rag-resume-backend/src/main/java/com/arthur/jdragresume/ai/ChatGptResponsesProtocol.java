package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Stateless protocol adapter for the official ChatGPT plan usage Responses API.
 * The caller must redact outbound messages and obtain an authorized, account-specific
 * OAuth token before sending. This class deliberately does not manage credentials.
 */
final class ChatGptResponsesProtocol {
    private static final String NAMESPACE = "resumelens";
    private final ObjectMapper mapper;

    ChatGptResponsesProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ObjectNode request(JsonNode chat) {
        ObjectNode body = mapper.createObjectNode();
        body.set("model", chat.get("model"));
        body.put("store", false);
        body.put("stream", true);
        ArrayNode input = body.putArray("input");
        for (JsonNode message : chat.path("messages")) {
            String role = message.path("role").asText();
            switch (role) {
                case "system", "developer", "user", "assistant" -> {
                    if (message.path("content").isTextual() && !message.path("content").asText().isEmpty()) {
                        input.addObject().put("role", "system".equals(role) ? "developer" : role)
                                .set("content", message.get("content"));
                    }
                    for (JsonNode call : message.path("tool_calls")) {
                        JsonNode function = call.get("function");
                        input.addObject().put("type", "function_call").put("namespace", NAMESPACE)
                                .set("call_id", call.get("id"));
                        ObjectNode item = (ObjectNode) input.get(input.size() - 1);
                        item.set("name", function.get("name"));
                        item.set("arguments", function.get("arguments"));
                    }
                }
                case "tool" -> {
                    ObjectNode item = input.addObject().put("type", "function_call_output");
                    item.set("call_id", message.get("tool_call_id"));
                    item.set("output", message.get("content"));
                }
                default -> throw new IllegalArgumentException("Unsupported message role: " + role);
            }
        }
        if (!chat.path("tools").isEmpty()) {
            ObjectNode namespace = body.putArray("tools").addObject()
                    .put("type", "namespace").put("name", NAMESPACE)
                    .put("description", "ResumeLens application tools");
            ArrayNode functions = namespace.putArray("tools");
            for (JsonNode tool : chat.path("tools")) {
                ObjectNode function = tool.get("function").deepCopy();
                function.put("type", "function");
                // Existing tool schemas allow optional arguments and are not strict schemas.
                function.put("strict", false);
                functions.add(function);
            }
            body.put("tool_choice", "auto");
        }
        if ("json_object".equals(chat.path("response_format").path("type").asText())) {
            body.putObject("text").putObject("format").put("type", "json_object");
        }
        return body;
    }

    /**
     * Consume SSE through a terminal event; partial text is never treated as success.
     * The completed event includes the full response, including tool calls and usage.
     */
    ObjectNode readCompleted(Reader source) throws IOException {
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        StringBuilder data = new StringBuilder();
        for (String line; (line = reader.readLine()) != null; ) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    ObjectNode result = event(mapper.readTree(data.toString()));
                    if (result != null) return result;
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (!data.isEmpty()) {
            ObjectNode result = event(mapper.readTree(data.toString()));
            if (result != null) return result;
        }
        throw new BusinessException("AI_REQUEST_ERROR", "AI stream ended without response.completed");
    }

    private ObjectNode event(JsonNode event) {
        return switch (event.path("type").asText()) {
            case "response.completed" -> completion(event.path("response"));
            case "response.failed", "error" -> {
                String code = event.path("response").path("error").path("code").asText(
                        event.path("code").asText());
                if ("subscription_sharing_usage_limit_exceeded".equals(code)
                        || "subscription_sharing_usage_unavailable".equals(code)) {
                    throw new BusinessException("AI_RATE_LIMITED", "ChatGPT plan usage is unavailable or exhausted");
                }
                throw new BusinessException("AI_REQUEST_FAILED", "ChatGPT inference failed");
            }
            case "response.incomplete" ->
                    throw new BusinessException("AI_REQUEST_FAILED", "ChatGPT inference was incomplete");
            default -> null;
        };
    }

    private ObjectNode completion(JsonNode response) {
        if (!response.path("output").isArray()) {
            throw new BusinessException("AI_RESPONSE_EMPTY", "ChatGPT response output is missing");
        }
        ObjectNode result = mapper.createObjectNode();
        ObjectNode choice = result.putArray("choices").addObject();
        ObjectNode message = choice.putObject("message").put("role", "assistant");
        StringBuilder text = new StringBuilder();
        ArrayNode calls = mapper.createArrayNode();
        for (JsonNode item : response.path("output")) {
            switch (item.path("type").asText()) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText())) text.append(part.path("text").asText());
                        if ("refusal".equals(part.path("type").asText())) {
                            throw new BusinessException("AI_REQUEST_FAILED", "ChatGPT refused the request");
                        }
                    }
                }
                case "function_call" -> {
                    if (!NAMESPACE.equals(item.path("namespace").asText())
                            || !item.path("call_id").isTextual() || !item.path("name").isTextual()
                            || !item.path("arguments").isTextual()) {
                        throw new BusinessException("AI_REQUEST_FAILED", "Invalid ChatGPT function call");
                    }
                    ObjectNode call = calls.addObject().put("type", "function");
                    call.set("id", item.get("call_id"));
                    ObjectNode function = call.putObject("function");
                    function.set("name", item.get("name"));
                    function.set("arguments", item.get("arguments"));
                }
                case "reasoning" -> { /* No provider reasoning text is persisted in the chat transcript. */ }
                default -> throw new BusinessException("AI_REQUEST_FAILED", "Unsupported ChatGPT output item");
            }
        }
        if (text.isEmpty()) message.putNull("content");
        else message.put("content", text.toString());
        if (!calls.isEmpty()) message.set("tool_calls", calls);
        choice.put("finish_reason", calls.isEmpty() ? "stop" : "tool_calls");
        JsonNode usage = response.path("usage");
        if (usage.isObject()) {
            ObjectNode tokens = result.putObject("usage");
            if (usage.has("input_tokens")) tokens.set("prompt_tokens", usage.get("input_tokens"));
            if (usage.has("output_tokens")) tokens.set("completion_tokens", usage.get("output_tokens"));
        }
        return result;
    }
}
