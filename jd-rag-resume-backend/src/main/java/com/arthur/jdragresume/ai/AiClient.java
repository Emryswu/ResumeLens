package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiClient {
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AiClient(AiProperties aiProperties, ObjectMapper objectMapper) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    public String chat(String systemPrompt, String userPrompt) {
        if (aiProperties.isMockEnabled()) {
            return mockAnalysisResponse();
        }
        validateConfig();
        Map<String, Object> body = Map.of(
                "model", aiProperties.getModel(),
                "temperature", 0.2,
                "thinking", Map.of("type", "disabled"),
                "response_format", Map.of("type", "json_object"),
                "max_tokens", 1200,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
        JsonNode content = send(body, timeout()).path("content");
        if (content.isMissingNode() || content.asText().isBlank()) {
            throw new BusinessException("AI_RESPONSE_EMPTY", "AI response content is empty");
        }
        return content.asText();
    }

    /**
     * Function-calling round used by the agent loop. Returns the raw assistant message
     * ({@code content} may be null when the model only emits {@code tool_calls}).
     * No JSON response_format here: tool calls and the final natural-language answer
     * would both be distorted by it. The caller passes the remaining turn budget as timeout.
     */
    public JsonNode chatWithTools(List<Map<String, Object>> messages, List<Map<String, Object>> tools, Duration timeout) {
        validateConfig();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", aiProperties.getModel());
        body.put("temperature", 0.2);
        body.put("thinking", Map.of("type", "disabled"));
        body.put("max_tokens", 1500);
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        JsonNode message = send(body, timeout);
        if (message.isMissingNode() || !message.isObject()) {
            throw new BusinessException("AI_RESPONSE_EMPTY", "AI response message is empty");
        }
        return message;
    }

    private JsonNode send(Map<String, Object> body, Duration requestTimeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(resolveChatCompletionsUrl()))
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerException(response.statusCode());
            }
            return objectMapper.readTree(response.body()).path("choices").path(0).path("message");
        } catch (BusinessException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new BusinessException("AI_TIMEOUT", "AI service timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI_REQUEST_INTERRUPTED", "AI request was interrupted");
        } catch (Exception ex) {
            throw new BusinessException("AI_REQUEST_ERROR", "failed to call AI service");
        }
    }

    /** Per-request ceiling from configuration; the agent loop never waits longer than this per model call. */
    public Duration timeout() {
        long seconds = aiProperties.getTimeoutSeconds() <= 0 ? 60 : aiProperties.getTimeoutSeconds();
        return Duration.ofSeconds(seconds);
    }

    private BusinessException providerException(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> new BusinessException("AI_AUTH_FAILED", "AI credentials were rejected");
            case 402 -> new BusinessException("AI_BALANCE_INSUFFICIENT", "AI account balance is insufficient");
            case 408, 504 -> new BusinessException("AI_TIMEOUT", "AI service timed out");
            case 429 -> new BusinessException("AI_RATE_LIMITED", "AI service rate limit was reached");
            default -> statusCode >= 500
                    ? new BusinessException("AI_SERVICE_UNAVAILABLE", "AI service is temporarily unavailable")
                    : new BusinessException("AI_REQUEST_FAILED", "AI service rejected the request with status " + statusCode);
        };
    }

    private void validateConfig() {
        if (isBlank(aiProperties.getApiKey()) || isBlank(aiProperties.getBaseUrl()) || isBlank(aiProperties.getModel())) {
            throw new BusinessException("AI_NOT_CONFIGURED", "AI_API_KEY, AI_BASE_URL and AI_MODEL must be configured");
        }
    }

    private String resolveChatCompletionsUrl() {
        String baseUrl = aiProperties.getBaseUrl().trim();
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return trimmed + "/v1/chat/completions";
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String mockAnalysisResponse() {
        return """
                {
                  "matchScore": 86.50,
                  "strengths": "简历体现了岗位所需的 Java、Spring Boot、MySQL、JWT 与 REST API 实践。[chunk-0]",
                  "missingSkills": "简历尚未充分体现高并发调优、自动化测试和生产部署经验。",
                  "improvementSuggestions": "补充项目指标、API 规模、数据库设计细节以及个人承担的具体职责。",
                  "interviewQuestions": "1. 请说明 JWT 鉴权流程。2. Spring Boot 如何统一处理异常？3. 数据表如何实现用户数据隔离？",
                  "summary": "候选人与 Java 后端及 RAG 工程岗位匹配度较高，但仍需补充工程化和生产环境经验。"
                }
                """;
    }
}
