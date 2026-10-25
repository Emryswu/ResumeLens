package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.exception.ResourceNotFoundException;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmitRecommendationsToolTests {
    private final ObjectMapper json = new ObjectMapper();
    private final JobDescriptionService jobs = mock(JobDescriptionService.class);
    private final JobSearchPreferencesService prefs = mock(JobSearchPreferencesService.class);
    private final AgentToolContext context = new AgentToolContext(new com.arthur.jdragresume.entity.AppUser());
    private final SubmitRecommendationsTool tool = new SubmitRecommendationsTool(jobs, prefs);

    @BeforeEach
    void setUp() {
        when(prefs.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5, null, null, false));
    }

    private JobDescriptionResponse job(long id, String depth, String description, String requirements) {
        return new JobDescriptionResponse(id, 1L, "Java 后端实习", "示例科技", "深圳", "实习", "在校生",
                description, requirements, "BOSS", "https://www.zhipin.com/job_detail/abc.html", "abc",
                null, null, depth, null, null);
    }

    private com.fasterxml.jackson.databind.JsonNode proposals(String... items) throws Exception {
        return json.readTree("{\"recommendations\":[" + String.join(",", items) + "]}");
    }

    private String item(long id, String evidence) throws Exception {
        return json.writeValueAsString(java.util.Map.of("jobId", id, "reason", "有 Java 后端技术栈", "evidence", evidence));
    }

    @Test
    void previouslyVerifiedFullJobPassesWithoutTurnLocalVerifiedIdsAndUsesUnclippedOriginal() throws Exception {
        String lateEvidence = "参与 Java 后端 API 开发";
        when(jobs.findById(8L)).thenReturn(job(8, "FULL", "说明文字".repeat(1500) + lateEvidence,
                "实习至少 3 个月，每周 5 天"));
        var args = proposals(item(8, lateEvidence));
        assertTrue(tool.parameters().validate(args).isEmpty());
        var result = (SubmitRecommendationsTool.Result) tool.execute(args, context);
        assertTrue(result.accepted());
        assertEquals(List.of(), result.recommendations().getFirst().unknownConditions());
        verify(jobs).findById(8L);
    }

    @Test
    void listingAndInventedCitationsAndForeignJobsAreRejected() throws Exception {
        when(jobs.findById(1L)).thenReturn(job(1, "LISTING", "Java 摘要", ""));
        when(jobs.findById(2L)).thenReturn(job(2, "FULL", "Java 开发", ""));
        when(jobs.findById(3L)).thenThrow(new ResourceNotFoundException("JobDescription", 3L));
        var error = assertThrows(BusinessException.class,
                () -> tool.execute(proposals(item(1, "Java 摘要"), item(2, "熟悉不存在的技术"), item(3, "证据")), context));
        assertEquals("RECOMMENDATIONS_REJECTED", error.getCode());
        assertTrue(error.getMessage().contains("jobId=1"));
        assertTrue(error.getMessage().contains("jobId=2"));
        assertTrue(error.getMessage().contains("jobId=3"));
    }

    @Test
    void hardConflictsRejectAndUnknownConditionsStayExplicit() throws Exception {
        when(jobs.findById(4L)).thenReturn(job(4, "FULL", "实习至少 7 个月，每周 5 天。负责 Java 开发", ""));
        var error = assertThrows(BusinessException.class,
                () -> tool.execute(proposals(item(4, "负责 Java 开发")), context));
        assertTrue(error.getMessage().contains("硬条件冲突"));

        when(jobs.findById(5L)).thenReturn(job(5, "FULL", "负责 Java 开发", "优先有 Redis"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(proposals(item(5, "负责 Java 开发")), context);
        assertTrue(result.accepted());
        assertTrue(result.recommendations().getFirst().unknownConditions().contains("实习时长"));
        assertTrue(result.recommendations().getFirst().unknownConditions().contains("每周到岗天数"));
    }

    @Test
    void invalidArrayShapesAndDuplicateIdsCannotPass() throws Exception {
        assertFalse(tool.parameters().validate(proposals()).isEmpty());
        assertFalse(tool.parameters().validate(json.readTree("{\"recommendations\":[{\"jobId\":1,\"reason\":\"ok\"}]}")).isEmpty());
        assertFalse(tool.parameters().validate(json.readTree("{\"recommendations\":[{\"jobId\":\"1\",\"reason\":\"ok\",\"evidence\":\"Java\"}]}")).isEmpty());
        when(jobs.findById(2L)).thenReturn(job(2, "FULL", "Java 开发", ""));
        var error = assertThrows(BusinessException.class,
                () -> tool.execute(proposals(item(2, "Java"), item(2, "Java")), context));
        assertTrue(error.getMessage().contains("重复"));
    }
}
