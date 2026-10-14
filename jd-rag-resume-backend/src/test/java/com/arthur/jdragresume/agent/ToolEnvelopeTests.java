package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolEnvelopeTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void truncatesOnlyTheTailItemsAndPreservesFieldsAndMetadata() throws Exception {
        List<Map<String, Object>> rows = List.of(Map.of("id", 1, "title", "Java"),
                Map.of("id", 2, "title", "MySQL"), Map.of("id", 3, "title", "Redis"));
        JsonNode data = mapper.valueToTree(Map.of("total", 20, "page", 0, "resumes", rows));
        int limit = mapper.valueToTree(Map.of("total", 20, "page", 0, "resumes", rows.subList(0, 2))).toString().length();

        JsonNode envelope = mapper.readTree(ToolEnvelope.ok("list_resumes", data, data.toString(), limit));

        assertTrue(envelope.path("ok").asBoolean());
        assertTrue(envelope.path("truncated").asBoolean());
        assertEquals(1, envelope.path("omittedItems").asInt());
        JsonNode kept = envelope.path("untrusted_data");
        assertTrue(kept.isObject());
        assertEquals(20, kept.path("total").asInt());
        assertEquals(0, kept.path("page").asInt());
        assertEquals(mapper.valueToTree(rows.subList(0, 2)), kept.path("resumes"));
        assertEquals(limit, kept.toString().length());
        assertEquals(3, data.path("resumes").size(), "the original result must remain intact");
    }

    @Test
    void topLevelArraysKeepTheirShapeAndReportAllOmittedItems() throws Exception {
        JsonNode data = mapper.valueToTree(List.of("a", "bb", "ccc", "dddd"));

        JsonNode envelope = mapper.readTree(ToolEnvelope.ok("list", data, data.toString(), 10));

        assertTrue(envelope.path("untrusted_data").isArray());
        assertEquals(mapper.valueToTree(List.of("a", "bb")), envelope.path("untrusted_data"));
        assertEquals(2, envelope.path("omittedItems").asInt());
    }

    @Test
    void fittingAndEmptyListsRemainUnchanged() throws Exception {
        for (JsonNode data : List.<JsonNode>of(mapper.valueToTree(List.of("a")), mapper.valueToTree(Map.of("resumes", List.of(), "total", 0)))) {
            JsonNode envelope = mapper.readTree(ToolEnvelope.ok("list", data, data.toString(), data.toString().length()));
            assertEquals(data, envelope.path("untrusted_data"));
            assertFalse(envelope.has("truncated"));
            assertFalse(envelope.has("omittedItems"));
        }
    }

    @Test
    void aSingleOversizedItemFailsInsteadOfLookingLikeAnEmptyList() {
        JsonNode data = mapper.valueToTree(Map.of("total", 1, "resumes", List.of(Map.of("title", "x".repeat(500)))));

        BusinessException rejected = assertThrows(BusinessException.class, () -> ToolEnvelope.ok("list_resumes", data, data.toString(), 100));

        assertEquals("TOOL_RESULT_TOO_LARGE", rejected.getCode());
    }
}
