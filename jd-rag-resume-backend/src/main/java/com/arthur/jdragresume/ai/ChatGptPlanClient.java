package com.arthur.jdragresume.ai;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Official subscription inference transport. An authorized access token is supplied
 * per invocation; it is never read from the shared API-key configuration.
 *
 * Not registered as a Spring bean until local account consent and credential
 * ownership are implemented. Callers must pass already-redacted messages.
 */
final class ChatGptPlanClient {
    private static final URI RESPONSES = URI.create("https://api.openai.com/v1/responses");
    private final ObjectMapper mapper;
    private final ChatGptResponsesProtocol protocol;
    private final HttpClient httpClient;

    ChatGptPlanClient(ObjectMapper mapper, Duration connectTimeout) {
        this.mapper = mapper;
        this.protocol = new ChatGptResponsesProtocol(mapper);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    JsonNode complete(JsonNode redactedChat, String accessToken, Duration timeout) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException("AI_NOT_CONFIGURED", "ChatGPT account authorization is required");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(RESPONSES)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(protocol.request(redactedChat))))
                    .build();
            // The existing Agent contract expects a complete reply, not individual deltas.
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw providerException(response.statusCode());
            }
            return protocol.readCompleted(new StringReader(response.body()));
        } catch (BusinessException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new BusinessException("AI_TIMEOUT", "ChatGPT service timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("AI_REQUEST_INTERRUPTED", "AI request was interrupted");
        } catch (Exception ex) {
            // Do not include request bodies, headers, credentials or provider payloads in errors.
            throw new BusinessException("AI_REQUEST_ERROR", "failed to call ChatGPT service");
        }
    }

    private static BusinessException providerException(int statusCode) {
        return switch (statusCode) {
            case 401, 403 -> new BusinessException("AI_AUTH_FAILED", "ChatGPT authorization was rejected");
            case 408, 504 -> new BusinessException("AI_TIMEOUT", "ChatGPT service timed out");
            case 429 -> new BusinessException("AI_RATE_LIMITED", "ChatGPT plan usage limit was reached");
            default -> statusCode >= 500
                    ? new BusinessException("AI_SERVICE_UNAVAILABLE", "ChatGPT service is temporarily unavailable")
                    : new BusinessException("AI_REQUEST_FAILED", "ChatGPT service rejected the request with status " + statusCode);
        };
    }
}
