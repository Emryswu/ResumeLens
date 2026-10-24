package com.arthur.jdragresume.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PatchrightJobCollectorHttpTests {
    @Test void backendUsesAuthenticatedHttpAndRelaysTheSharedOperationLog() throws Exception {
        var json = new ObjectMapper();
        var requests = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tasks", exchange -> {
            assertEquals("Bearer local-test-secret", exchange.getRequestHeaders().getFirst("Authorization"));
            String response;
            if (exchange.getRequestMethod().equals("POST")) {
                requests.add(json.readTree(exchange.getRequestBody()));
                response = "{\"type\":\"accepted\"}";
            } else {
                response = json.writeValueAsString(Map.of("type", "result", "task", Map.of("status", "completed", "phase", "read",
                        "finishReason", "NO_RESULTS", "pagesRead", 0, "pagesLoaded", 1, "pageLog", List.of(), "jobs", List.of(),
                        "skippedWithoutId", 0, "command", Map.of("keyword", "Java", "city", "深圳", "maxPages", 1))));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var budget = mock(BossPageBudget.class);
            var events = new ArrayList<CollectionSession.Progress>();
            var session = new CollectionSession(1L, "42", "42:turn:call", "call", CollectorMode.PATCHRIGHT, System.currentTimeMillis() + 5000, events::add);
            when(budget.reserve(eq(session), eq(session.reservationId()), eq(1))).thenReturn(new BossPageBudget.Grant(session.reservationId(), 1, 29, LocalDate.of(2026, 10, 25)));
            when(budget.finish(session, session.reservationId(), 1)).thenReturn(29);
            var collector = new PatchrightJobCollector("http://127.0.0.1:" + server.getAddress().getPort(), "local-test-secret", json,
                    new BrowserExtensionJobCollector(), budget);
            var result = assertInstanceOf(JobCollector.Collected.class, collector.start(new CollectRequest("Java", "深圳", 1), session));
            assertEquals("NO_RESULTS", result.result().code());
            assertEquals(29, result.result().dailyPagesLeft());
            assertEquals(1, requests.size());
            assertEquals(1, requests.getFirst().path("pageAllowance").asInt());
            assertEquals("2026-10-25", requests.getFirst().path("budgetDate").asText());
            assertEquals("42", requests.getFirst().path("conversationId").asText());
            assertEquals("connecting", events.getFirst().progress().path("phase").asText());
            assertEquals("NO_RESULTS", events.getLast().progress().path("finishReason").asText());
        } finally { server.stop(0); }
    }
}
