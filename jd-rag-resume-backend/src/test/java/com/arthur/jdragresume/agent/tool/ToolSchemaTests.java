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
}
