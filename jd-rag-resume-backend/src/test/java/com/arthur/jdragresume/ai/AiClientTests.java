package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.agent.AgentMessage;
import com.arthur.jdragresume.agent.AgentModel;
import com.arthur.jdragresume.agent.OpenAiCompatibleAgentModel;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsDeepSeekCompatibleJsonRequest() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });

        AiClient client = new AiClient(properties(), objectMapper);

        assertEquals("ok", client.chat("system", "user"));
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("deepseek-flash", body.path("model").asText());
        assertEquals("disabled", body.path("thinking").path("type").asText());
        assertEquals("json_object", body.path("response_format").path("type").asText());
        assertFalse(body.path("messages").isEmpty());
    }

    @Test
    void analysisCompletionCarriesTheFinishReasonAndTokenUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"choices":[{"message":{"content":"{\\"matchScore\\": 8"},"finish_reason":"length"}],
                     "usage":{"prompt_tokens":1834,"completion_tokens":1200,"total_tokens":3034}}
                    """);
        });

        AiClient.Completion completion = new AiClient(properties(), objectMapper).complete("system", "user");

        assertEquals("{\"matchScore\": 8", completion.content());
        assertEquals("length", completion.finishReason());
        assertEquals(1834, completion.promptTokens());
        assertEquals(1200, completion.completionTokens());
        assertEquals(AiClient.ANALYSIS_MAX_TOKENS, objectMapper.readTree(requestBody.get()).path("max_tokens").asInt());
    }

    @Test
    void analysisCompletionToleratesAProviderThatReportsNoUsage() throws Exception {
        startServer(exchange -> respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));

        AiClient.Completion completion = new AiClient(properties(), objectMapper).complete("system", "user");

        assertEquals("ok", completion.content());
        assertNull(completion.finishReason());
        assertNull(completion.promptTokens());
        assertNull(completion.completionTokens());
    }

    @Test
    void anEmptyAnalysisReplyIsReturnedWithItsUsageInsteadOfThrown() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
                {"choices":[{"message":{"content":""},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1624,"completion_tokens":0}}
                """));

        AiClient.Completion completion = new AiClient(properties(), objectMapper).complete("system", "user");

        assertEquals("", completion.content());
        assertEquals("stop", completion.finishReason());
        assertEquals(0, completion.completionTokens());
    }

    @Test
    void aNullContentIsAnEmptyReplyNotTheWordNull() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":null},\"finish_reason\":\"stop\"}]}"));

        assertEquals("", new AiClient(properties(), objectMapper).complete("system", "user").content());
    }

    @Test
    void chatStillRejectsAnEmptyReplyAndNamesItsFinishReason() throws Exception {
        startServer(exchange -> respond(exchange, 200,
                "{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"length\"}]}"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> new AiClient(properties(), objectMapper).chat("system", "user")
        );

        assertEquals("AI_RESPONSE_EMPTY", exception.getCode());
        assertTrue(exception.getMessage().contains("finish_reason=length"), exception.getMessage());
    }

    @Test
    void analysisBudgetLeavesRoomForAFullReport() {
        // Measured 2026-10-07 with deepseek-flash: complete reports used 1109-1112 completion tokens and 8 of 10
        // replies hit the old 1200 limit (finish_reason=length). Keep well above that.
        assertTrue(AiClient.ANALYSIS_MAX_TOKENS >= 2 * 1200, "max_tokens=" + AiClient.ANALYSIS_MAX_TOKENS);
    }

    @Test
    void mapsProviderRateLimitToStableErrorCode() throws Exception {
        startServer(exchange -> respond(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> new AiClient(properties(), objectMapper).chat("system", "user")
        );

        assertEquals("AI_RATE_LIMITED", exception.getCode());
    }

    @Test
    void toolRoundDefaultsToHighThinkingWithoutJsonMode() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
        });

        JsonNode message = new AiClient(properties(), objectMapper).chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")),
                List.of(Map.of("type", "function", "function", Map.of("name", "list_resumes"))),
                Duration.ofSeconds(5)
        );

        assertEquals("done", message.path("content").asText());
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("list_resumes", body.path("tools").path(0).path("function").path("name").asText());
        assertEquals("auto", body.path("tool_choice").asText());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertTrue(body.path("max_tokens").asInt() >= 8192, "thinking shares the output token budget");
        // JSON mode would force the final natural-language answer into an object.
        assertTrue(body.path("response_format").isMissingNode());
    }

    @Test
    void agentModelParsesToolCallsWhenContentIsNull() throws Exception {
        startServer(exchange -> respond(exchange, 200, """
                {"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"read the resume first","tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"list_resumes","arguments":"{\\"keyword\\":\\"java\\"}"}}
                ]}}]}
                """));

        AgentModel.Reply reply = new OpenAiCompatibleAgentModel(new AiClient(properties(), objectMapper))
                .next("system", List.of(AgentMessage.user("hi")), List.of(), Duration.ofSeconds(5));

        assertNull(reply.content());
        assertEquals("read the resume first", reply.reasoningContent());
        assertEquals(1, reply.toolCalls().size());
        assertEquals("call_1", reply.toolCalls().get(0).id());
        assertEquals("list_resumes", reply.toolCalls().get(0).name());
        assertEquals("{\"keyword\":\"java\"}", reply.toolCalls().get(0).arguments());
    }

    @ParameterizedTest
    @EnumSource(ReasoningEffort.class)
    void chosenEffortReachesTheProviderAndKeepsEarlierReasoning(ReasoningEffort effort) throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}");
        });
        List<AgentMessage> transcript = List.of(AgentMessage.user("hi"),
                AgentMessage.assistant("earlier answer", null, "earlier reasoning"), AgentMessage.user("continue"));
        new OpenAiCompatibleAgentModel(new AiClient(properties(), objectMapper)).next("system", transcript,
                List.of(), Duration.ofSeconds(5), effort);

        JsonNode body = objectMapper.readTree(requestBody.get());
        assertEquals("earlier reasoning", body.path("messages").path(2).path("reasoning_content").asText());
        assertEquals(effort == ReasoningEffort.OFF ? "disabled" : "enabled", body.path("thinking").path("type").asText());
        if (effort == ReasoningEffort.OFF) {
            assertTrue(body.path("reasoning_effort").isMissingNode());
        } else {
            assertEquals(effort.providerValue(), body.path("reasoning_effort").asText());
        }
        assertEquals("", AgentMessage.assistant("old non-thinking reply", null).toProviderMessage().get("reasoning_content"));
    }

    @Test
    void modelToolIdLimitMatchesTheClientTranscriptLimit() throws Exception {
        AtomicReference<String> id = new AtomicReference<>("call_" + "x".repeat(58) + "-");
        startServer(exchange -> respond(exchange, 200, objectMapper.writeValueAsString(Map.of("choices", List.of(
                Map.of("message", Map.of("tool_calls", List.of(Map.of("id", id.get(), "type", "function",
                        "function", Map.of("name", "list_resumes", "arguments", "{}"))))))))));
        OpenAiCompatibleAgentModel model = new OpenAiCompatibleAgentModel(new AiClient(properties(), objectMapper));

        AgentModel.Reply reply = model.next("system", List.of(AgentMessage.user("hi")), List.of(), Duration.ofSeconds(5));
        assertEquals(id.get(), reply.toolCalls().getFirst().id());
        id.set(id.get() + "x");
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> model.next("system", List.of(AgentMessage.user("hi")), List.of(), Duration.ofSeconds(5)));
        assertEquals("AI_RESPONSE_PARSE_FAILED", rejected.getCode());
    }

    @Test
    void toolRoundHonoursTheCallersRemainingBudget() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
        });

        // Configured per-request timeout is 5s; the loop's remaining budget (300ms) must win.
        BusinessException exception = assertThrows(BusinessException.class, () -> new AiClient(properties(), objectMapper)
                .chatWithTools(List.of(Map.of("role", "user", "content", "hi")), List.of(), Duration.ofMillis(300)));

        assertEquals("AI_TIMEOUT", exception.getCode());
    }

    @Test
    void finalHttpBoundaryRedactsAnalysisAndEveryAgentPayloadWithoutMutatingItsInput() throws Exception {
        var received = new java.util.ArrayList<String>();
        startServer(exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
        });
        String raw = "13812345678 arthur@example.com";
        AiClient client = new AiClient(properties(), objectMapper);
        client.complete("system " + raw, "resume/JD " + raw);
        var messages = List.<Map<String, Object>>of(Map.of("role", "user", "content", raw),
                Map.of("role", "assistant", "reasoning_content", raw, "tool_calls", List.of(
                        Map.of("id", "call_19987654321", "function", Map.of("name", "lookup", "arguments", "{\"contact\":\"" + raw + "\"}")))),
                Map.of("role", "tool", "tool_call_id", "call_19987654321", "content", "resume/chunk/BOSS JD " + raw));
        client.chatWithTools(messages, List.of(Map.of("description", raw)), Duration.ofSeconds(5));
        assertEquals(2, received.size());
        for (String body : received) {
            assertFalse(body.contains("13812345678"));
            assertFalse(body.contains("arthur@example.com"));
            assertTrue(body.contains("[手机号] [邮箱]"));
        }
        assertEquals(raw, messages.getFirst().get("content"));
        var agentMessages = objectMapper.readTree(received.getLast()).path("messages");
        assertEquals("call_19987654321", agentMessages.path(1).path("tool_calls").path(0).path("id").asText());
        assertEquals("call_19987654321", agentMessages.path(2).path("tool_call_id").asText());
    }

    @Test
    void mockAnalysisStillNeedsNoConfigurationOrNetworkAndReturnsTheSameReport() {
        AiProperties properties = new AiProperties();
        properties.setMockEnabled(true);
        var client = new AiClient(properties, objectMapper);
        assertEquals(client.complete("system", "resume"),
                client.complete("13812345678", "arthur@example.com"));
    }

    private AiProperties properties() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("deepseek-flash");
        properties.setTimeoutSeconds(5);
        properties.setMockEnabled(false);
        return properties;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
