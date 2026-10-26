package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
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
    void listingInventedCitationsAndForeignJobsAreIndividuallyRejected() throws Exception {
        when(jobs.findById(1L)).thenReturn(job(1, "LISTING", "Java 摘要", ""));
        when(jobs.findById(2L)).thenReturn(job(2, "FULL", "负责 Java 开发", ""));
        when(jobs.findById(3L)).thenThrow(new ResourceNotFoundException("JobDescription", 3L));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(1, "列表里的 Java 摘要"), item(2, "熟悉不存在的技术"), item(3, "候选岗位的证据文本")), context);
        assertFalse(result.accepted());
        assertTrue(result.recommendations().isEmpty());
        assertEquals(List.of(1L, 2L, 3L), result.rejected().stream().map(SubmitRecommendationsTool.Rejected::jobId).toList());
        assertTrue(result.rejected().get(0).reason().contains("FULL"));
        assertTrue(result.rejected().get(1).reason().contains("原文"));
        assertTrue(result.rejected().get(2).reason().contains("无权访问"));
    }

    @Test
    void successfulItemsSurviveFailuresAndUnknownConditionsStayExplicit() throws Exception {
        when(jobs.findById(4L)).thenReturn(job(4, "FULL", "实习至少 7 个月，每周 5 天。负责 Java 开发", ""));
        when(jobs.findById(5L)).thenReturn(job(5, "FULL", "负责 Java 后端开发", "优先有 Redis"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(4, "负责 Java 开发"), item(5, "负责 Java 后端开发")), context);
        assertFalse(result.accepted());
        assertEquals(List.of(5L), result.recommendations().stream().map(SubmitRecommendationsTool.Submitted::jobId).toList());
        assertEquals(4L, result.rejected().getFirst().jobId());
        assertTrue(result.rejected().getFirst().reason().contains("硬条件冲突"));
        assertTrue(result.recommendations().getFirst().unknownConditions().contains("实习时长"));
        assertTrue(result.recommendations().getFirst().unknownConditions().contains("每周到岗天数"));
    }

    @Test
    void whitespaceDifferencesPassButTooShortAndTitleOnlyEvidenceFail() throws Exception {
        when(jobs.findById(6L)).thenReturn(job(6, "FULL",
                "职责：参与 Java    后端\n系统开发，负责接口设计", "实习至少 3 个月，每周 5 天"));
        var whitespace = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(6, "参与 Java 后端 系统开发")), context);
        assertTrue(whitespace.accepted(), whitespace.rejected().toString());
        when(jobs.findById(7L)).thenReturn(job(7, "FULL",
                "岗位职责：负责 Java 后端实习的服务开发", ""));
        var shortOnly = (SubmitRecommendationsTool.Result) tool.execute(proposals(item(7, "Java")), context);
        assertEquals("JD 证据片段至少需要 8 个字符", shortOnly.rejected().getFirst().reason());
        var titleOnly = (SubmitRecommendationsTool.Result) tool.execute(proposals(item(7, "Java 后端实习")), context);
        assertTrue(titleOnly.rejected().getFirst().reason().contains("职位标题"));
    }

    @Test
    void invalidArrayShapesAndDuplicateIdsCannotPass() throws Exception {
        assertFalse(tool.parameters().validate(proposals()).isEmpty());
        assertFalse(tool.parameters().validate(json.readTree("{\"recommendations\":[{\"jobId\":1,\"reason\":\"ok\"}]}")).isEmpty());
        assertFalse(tool.parameters().validate(json.readTree("{\"recommendations\":[{\"jobId\":\"1\",\"reason\":\"ok\",\"evidence\":\"Java\"}]}")).isEmpty());
        when(jobs.findById(2L)).thenReturn(job(2, "FULL", "负责 Java 后端系统开发", ""));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(2, "负责 Java 后端系统开发"), item(2, "负责 Java 后端系统开发")), context);
        assertEquals(1, result.recommendations().size());
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().getFirst().reason().contains("重复"));
    }

    @Test
    void unmetPreferredEducationIsSubmittedWithWarningButNoBlock() throws Exception {
        when(prefs.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5,
                null, null, false, 2, null, 0, true));
        when(jobs.findById(51L)).thenReturn(job(51, "FULL",
                "参与 Java 后端开发，负责服务端接口维护", "实习至少3个月；每周5天；硕士优先"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(51, "参与 Java 后端开发")), context);
        assertTrue(result.accepted());
        assertEquals(1, result.recommendations().size());
        assertFalse(result.recommendations().getFirst().preferredWarnings().isEmpty());
        assertFalse(result.recommendations().getFirst().unknownConditions().contains("学历"));
    }

    @Test
    void missingEducationAcceptsAsUnknownWithProfileHintAndAbsentRequirementsPass() throws Exception {
        when(prefs.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5, null, null, false));
        when(jobs.findById(52L)).thenReturn(job(52, "FULL",
                "负责 Java 后端开发；实习至少3个月；每周5天", "学历要求本科及以上"));
        when(jobs.findById(53L)).thenReturn(job(53, "FULL",
                "负责 Java 后端开发；实习至少3个月；每周5天", "经验不限"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(52, "负责 Java 后端开发"), item(53, "负责 Java 后端开发")), context);
        assertTrue(result.accepted());
        var missing = result.recommendations().getFirst();
        assertTrue(missing.unknownConditions().contains("学历"));
        assertTrue(missing.missingUserFields().contains("学历"));
        var noThreshold = result.recommendations().get(1);
        assertFalse(noThreshold.unknownConditions().contains("学历"));
        assertFalse(noThreshold.unknownConditions().contains("工作经验"));
        assertTrue(noThreshold.jdNotes().contains("JD 未写学历要求"));
        assertTrue(noThreshold.jdNotes().contains("JD 未写经验要求"));
    }


    @Test
    void quantifiedDevelopmentExperienceIsARecommendationNoteNotUnknown() throws Exception {
        when(prefs.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5,
                null, null, false, 2, null, 0, true));
        when(jobs.findById(54L)).thenReturn(job(54, "FULL",
                "负责 Java 后端开发；实习至少3个月；每周5天",
                "1年以上Java开发经验"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(54, "负责 Java 后端开发")), context);
        assertTrue(result.accepted());
        assertTrue(result.rejected().isEmpty());
        var recommended = result.recommendations().getFirst();
        assertFalse(recommended.unknownConditions().contains("工作经验"));
        assertTrue(recommended.jdNotes().stream().anyMatch(note ->
                note.contains("JD 要求 1 年开发经验") && note.contains("需自行判断项目经历是否算数")));
        assertTrue(recommended.missingUserFields().isEmpty());
    }

    @Test
    void unquantifiedSkillExperienceDoesNotCreateUnknownOrAWarning() throws Exception {
        when(prefs.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5,
                null, null, false, 2, null, 0, true));
        when(jobs.findById(55L)).thenReturn(job(55, "FULL",
                "负责 Java 后端开发；实习至少3个月；每周5天",
                "有 Spring Boot 项目经验优先"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(55, "负责 Java 后端开发")), context);
        assertTrue(result.accepted());
        assertFalse(result.recommendations().getFirst().unknownConditions().contains("工作经验"));
        assertEquals(List.of("JD 未写学历要求", "JD 未写经验要求"),
                result.recommendations().getFirst().jdNotes());
    }

    @Test
    void formalWorkExperienceStillRejectsZeroYearStudent() throws Exception {
        when(prefs.forUser(null)).thenReturn(new JobSearchPreferencesView(true, 6, 5,
                null, null, false, 2, null, 0, true));
        when(jobs.findById(56L)).thenReturn(job(56, "FULL",
                "负责 Java 后端开发；实习至少3个月；每周5天", "3年以上工作经验"));
        var result = (SubmitRecommendationsTool.Result) tool.execute(
                proposals(item(56, "负责 Java 后端开发")), context);
        assertFalse(result.accepted());
        assertTrue(result.recommendations().isEmpty());
        assertEquals(56L, result.rejected().getFirst().jobId());
    }

}
