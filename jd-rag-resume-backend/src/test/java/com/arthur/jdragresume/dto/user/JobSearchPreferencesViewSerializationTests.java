package com.arthur.jdragresume.dto.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobSearchPreferencesViewSerializationTests {
    @Test
    void graduationDateSerializesAsIsoStringWithAPlainObjectMapper() throws Exception {
        var view = new JobSearchPreferencesView(true, 6, 5, null, null, false,
                2, LocalDate.of(2027, 6, 30), 0, true);
        var json = new ObjectMapper().valueToTree(view);
        assertEquals("2027-06-30", json.path("expectedGraduationDate").asText());
    }
}
