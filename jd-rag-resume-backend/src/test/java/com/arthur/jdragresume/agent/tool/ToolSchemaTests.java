package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolSchemaTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolSchema schema = ToolSchema.object().integer("id", "record id", true, 1, Long.MAX_VALUE);

    @Test
    void acceptsAnIdInRange() throws Exception {
        assertEquals(List.of(), schema.validate(objectMapper.readTree("{\"id\":7}")));
    }

    @Test
    void integersBeyondLongAreRejectedInsteadOfWrappingAround() throws Exception {
        // 2^64 + 1: asLong() keeps the low 64 bits and would read it as id 1.
        List<String> problems = schema.validate(objectMapper.readTree("{\"id\":18446744073709551617}"));

        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void integerArraysCheckTypeSizeAndEveryItem() throws Exception {
        ToolSchema ids = ToolSchema.object().integerArray("jobIds", "ids", false, 1, Long.MAX_VALUE, 3);

        assertEquals(List.of(), ids.validate(objectMapper.readTree("{\"jobIds\":[1,2,3]}")));
        assertEquals(List.of(), ids.validate(objectMapper.readTree("{}")));
        assertEquals(1, ids.validate(objectMapper.readTree("{\"jobIds\":[1,2,3,4]}")).size());
        assertEquals(1, ids.validate(objectMapper.readTree("{\"jobIds\":[1,\"2\"]}")).size());
        assertEquals(1, ids.validate(objectMapper.readTree("{\"jobIds\":[0]}")).size());
        assertEquals(1, ids.validate(objectMapper.readTree("{\"jobIds\":7}")).size());
        assertEquals("integer", ids.toJson().path("properties").path("jobIds").path("items").path("type").asText());
        assertEquals(3, ids.toJson().path("properties").path("jobIds").path("maxItems").asInt());
    }
}
