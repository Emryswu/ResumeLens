package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListResumesToolTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @Test
    void listingIncludesTheUpdateTimeAndDirectionEvidence() {
        var service = mock(ResumeService.class);
        ResumeResponse resume = mapper.convertValue(Map.of("id", 1L, "title", "简历 v2", "rawText", "专业技能：Java Spring Boot Redis",
                "updatedAt", "2026-10-22T10:00:00"), ResumeResponse.class);
        when(service.findAllForAgent(0, 5, "")).thenReturn(new PageResponse<>(List.of(resume), 0, 20, 1, 1, true, true));
        var data = mapper.valueToTree(new ListResumesTool(service).execute(mapper.createObjectNode(), new AgentToolContext(new AppUser())));
        var row = data.path("resumes").path(0);
        assertTrue(row.hasNonNull("updatedAt"));
        assertTrue(row.path("directionEvidence").asText().contains("Java"));
    }

    @Test
    void skillEvidenceIsBoundedAndCanBeFoundAfterALongIntroduction() {
        var service = mock(ResumeService.class);
        String text = "基本介绍\n" + "简介内容".repeat(150) + "\n## 专业技能\nJava、Spring Boot、Redis。".repeat(40);
        var resume = mapper.convertValue(Map.of("id", 1L, "title", "简历", "rawText", text), ResumeResponse.class);
        when(service.findAllForAgent(1, 5, "Java")).thenReturn(new PageResponse<>(List.of(resume), 1, 5, 6, 2, false, true));
        var result = mapper.valueToTree(new ListResumesTool(service).execute(
                mapper.createObjectNode().put("page", 1).put("keyword", "Java"), new AgentToolContext(new AppUser())));
        String evidence = result.path("resumes").path(0).path("directionEvidence").asText();
        assertTrue(evidence.contains("Java"));
        assertFalse(evidence.contains("简介内容"));
        assertTrue(evidence.length() <= 301);
        assertEquals(1, result.path("page").asInt());
        assertEquals(2, result.path("totalPages").asInt());
        verify(service).findAllForAgent(1, 5, "Java");
    }
}
