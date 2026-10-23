package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.JobApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListJobApplicationsToolTests {
    @Test
    void readsSavedFeedbackWithoutWritingOrClaimingSync() {
        var service = mock(JobApplicationService.class);
        when(service.list()).thenReturn(List.of(
                new JobApplicationService.View(12L, "Java 实习生", "星河", "NOT_INTERESTED", "时间不合适", null)));
        var tool = new ListJobApplicationsTool(service);
        var mapper = new ObjectMapper();
        var result = mapper.valueToTree(tool.execute(mapper.createObjectNode(), new AgentToolContext(new AppUser())));
        assertEquals(1, result.path("returned").asInt());
        assertEquals("NOT_INTERESTED", result.path("applications").path(0).path("status").asText());
        assertTrue(tool.description().contains("不是 BOSS 实时"));
        verify(service).list();
        verifyNoMoreInteractions(service);
    }
}
