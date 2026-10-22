package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.collect.JobCollectionHistory;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchJobsToolTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final JobDescriptionService jobs = mock(JobDescriptionService.class);
    private final AgentToolContext context = new AgentToolContext(new AppUser());
    private final JobCollectionHistory history = mock(JobCollectionHistory.class);

    @Test
    void zeroResultsMustIncludeTheLibraryScope() {
        when(jobs.findAll(0, 10, "实习", "实习")).thenReturn(new PageResponse<>(List.of(), 0, 10, 0, 0, true, true));
        when(jobs.libraryCounts()).thenReturn(new JobDescriptionService.LibraryCounts(3, Map.of("全职", 2L, "未标注", 1L)));
        when(history.latest()).thenReturn(Optional.of(new JobCollectionHistory.Latest("BOSS", "Java 后端", "深圳", "在校生",
                2, 2, "COMPLETED", LocalDateTime.of(2026, 10, 22, 12, 0))));
        var result = mapper.valueToTree(new SearchJobsTool(jobs, history).execute(
                mapper.createObjectNode().put("keyword", "实习").put("employmentType", "实习"), context));
        assertTrue(result.has("libraryContext"), "zero results must explain the library scope");
        var scope = result.path("libraryContext");
        assertEquals(3, scope.path("total").asInt());
        assertEquals(2, scope.path("byEmploymentType").path("全职").asInt());
        assertEquals("Java 后端", scope.path("latestCollection").path("keyword").asText());
        assertEquals("深圳", scope.path("latestCollection").path("city").asText());
        assertEquals("在校生", scope.path("latestCollection").path("experience").asText());
        assertTrue(scope.path("note").asText().contains("尚未采集"));
        verify(jobs).findAll(0, 10, "实习", "实习");
    }

    @Test
    void aLibraryWithoutRecordedCollectionConditionsReportsUnknownExplicitly() {
        when(jobs.findAll(0, 10, "", "")).thenReturn(new PageResponse<>(List.of(), 0, 10, 0, 0, true, true));
        when(jobs.libraryCounts()).thenReturn(new JobDescriptionService.LibraryCounts(0, Map.of()));
        when(history.latest()).thenReturn(Optional.empty());
        var result = mapper.valueToTree(new SearchJobsTool(jobs, history).execute(mapper.createObjectNode(), context));
        assertTrue(result.path("libraryContext").path("latestCollection").isNull());
        assertEquals(0, result.path("libraryContext").path("total").asInt());
    }

    @Test
    void matchingJobsIncludeTheirEmploymentType() {
        JobDescriptionResponse row = mapper.convertValue(Map.of("id", 4L, "title", "开发实习生", "employmentType", "实习"), JobDescriptionResponse.class);
        when(jobs.findAll(0, 10, "", "实习")).thenReturn(new PageResponse<>(List.of(row), 0, 10, 1, 1, true, true));
        var result = mapper.valueToTree(new SearchJobsTool(jobs, history).execute(
                mapper.createObjectNode().put("employmentType", "实习"), context));
        assertEquals("实习", result.path("jobs").path(0).path("employmentType").asText());
        assertFalse(result.has("libraryContext"));
        verifyNoInteractions(history);
    }
}
