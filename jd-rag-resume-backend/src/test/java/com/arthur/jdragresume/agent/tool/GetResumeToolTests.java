package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.AgentLoop;
import com.arthur.jdragresume.agent.AgentMessage;
import com.arthur.jdragresume.agent.AgentModel;
import com.arthur.jdragresume.agent.AgentProperties;
import com.arthur.jdragresume.agent.TranscriptPolicy;
import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetResumeToolTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResumeService resumes = mock(ResumeService.class);
    private final GetResumeTool tool = new GetResumeTool(resumes);
    private final AgentToolContext context = new AgentToolContext(new AppUser());

    private ResumeResponse resume(String text) {
        return mapper.convertValue(java.util.Map.of("id", 43L, "title", "后端简历", "rawText", text), ResumeResponse.class);
    }

    @Test
    void aSingleResumeCanBeReadWithoutAnyArguments() {
        var row = resume("Spring Boot 和 Python 项目");
        when(resumes.findAll(0, 1, "")).thenReturn(new PageResponse<>(List.of(row), 0, 1, 1, 1, true, true));
        when(resumes.findById(43L)).thenReturn(row);
        assertTrue(tool.parameters().validate(mapper.createObjectNode()).isEmpty());
        var result = mapper.valueToTree(tool.execute(mapper.createObjectNode(), context));
        assertEquals(43, result.path("resumeId").asInt());
        assertEquals("Spring Boot 和 Python 项目", result.path("text").asText());
        assertFalse(result.path("truncated").asBoolean());
        verify(resumes).findById(43L);
    }

    @Test
    void aThreeThousandCharacterResumeIsNotTruncated() {
        String text = "Java、Spring Boot、MySQL、Redis，负责接口开发与测试。".repeat(100).substring(0, 3000);
        when(resumes.findById(43L)).thenReturn(resume(text));
        var result = mapper.valueToTree(tool.execute(mapper.createObjectNode().put("resumeId", 43), context));
        assertEquals(text, result.path("text").asText());
        assertFalse(result.path("truncated").asBoolean());
    }

    @Test
    void theLoopCanDeliverTheFullFiveThousandCharacterResumeToTheModel() {
        // Quotes/newlines need JSON escaping as well as room for the 5000-character body.
        String text = ("Java 项目：\"支付接口\"\nSpring Boot 和 MySQL；").repeat(200).substring(0, 5000);
        when(resumes.findById(43L)).thenReturn(resume(text));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        AgentModel model = (system, transcript, tools, timeout) -> {
            if (calls.incrementAndGet() == 1) return AgentModel.Reply.call(
                    AgentMessage.ToolCall.of("resume", "get_resume", "{\"resumeId\":43}"));
            try {
                var envelope = mapper.readTree(transcript.getLast().content());
                assertTrue(envelope.path("ok").asBoolean(), envelope.toString());
                assertEquals(text, envelope.path("untrusted_data").path("text").asText());
                assertFalse(envelope.path("untrusted_data").path("truncated").asBoolean());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new AssertionError(ex);
            }
            return AgentModel.Reply.answer("可以结合你的项目考虑后端开发。");
        };
        var properties = new AgentProperties();
        var registry = new AgentToolRegistry(List.of(tool));
        var loop = new AgentLoop(model, registry, mapper, properties, System::currentTimeMillis);
        var input = new TranscriptPolicy(properties).normalize(
                List.of(AgentMessage.user("读我的简历")), null);
        assertEquals(AgentLoop.State.COMPLETED,
                loop.run(input, null, context, (event, data) -> {}, () -> false).state());
        assertEquals(2, calls.get());
    }

    @Test
    void longResumeTextIsBoundedAndTruncationIsExplicit() {
        when(resumes.findById(43L)).thenReturn(resume("技能".repeat(4000)));
        var result = mapper.valueToTree(tool.execute(mapper.createObjectNode().put("resumeId", 43), context));
        assertTrue(result.path("text").asText().length() <= GetResumeTool.TEXT_LIMIT);
        assertTrue(result.path("truncated").asBoolean());
        verify(resumes, never()).findAll(anyInt(), anyInt(), anyString());
    }

    @Test
    void missingAndMultipleResumesAskForExplicitResolution() {
        for (long total : List.of(0L, 2L)) {
            when(resumes.findAll(0, 1, "")).thenReturn(new PageResponse<>(List.of(), 0, 1, total, 1, true, true));
            var error = assertThrows(BusinessException.class, () -> tool.execute(mapper.createObjectNode(), context));
            assertEquals(total == 0 ? "RESUME_NOT_FOUND" : "RESUME_SELECTION_REQUIRED", error.getCode());
        }
        verify(resumes, never()).findById(anyLong());
    }

    @Test
    void anExplicitIdStillUsesTheOwnershipCheckingService() {
        when(resumes.findById(99L)).thenThrow(new ResourceNotFoundException("resume", 99L));
        assertThrows(ResourceNotFoundException.class, () -> tool.execute(mapper.createObjectNode().put("resumeId", 99), context));
    }
}
