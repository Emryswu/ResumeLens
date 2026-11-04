package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.JobSemanticMatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RankJobsForResumeToolTests {
    private final JobSemanticMatchService ranking = mock(JobSemanticMatchService.class);
    private final RankJobsForResumeTool tool = new RankJobsForResumeTool(ranking);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fullLimitStillReportsRequestedIdsMissingFromTheUsersLibrary() throws Exception {
        var ids = List.of(10L, 11L, 404L);
        when(ranking.rank(1L, 3, ids)).thenReturn(List.of(
                new JobSemanticMatchResponse(job(10L), 0.9),
                new JobSemanticMatchResponse(job(11L), 0.8)));
        var result = (Map<?, ?>) tool.execute(
                json.readTree("{\"resumeId\":1,\"limit\":2,\"jobIds\":[10,11,404]}"),
                new AgentToolContext(new AppUser()));
        assertEquals(2, ((List<?>) result.get("matches")).size());
        assertEquals(List.of(404L), result.get("notInLibrary"));
    }

    @Test
    void lowerRankedExistingIdsAreNotReportedMissingWhenLimitIsSmallerThanTheRequest() throws Exception {
        var ids = List.of(10L, 11L, 12L);
        when(ranking.rank(1L, 3, ids)).thenReturn(List.of(
                new JobSemanticMatchResponse(job(10L), 0.9),
                new JobSemanticMatchResponse(job(11L), 0.8),
                new JobSemanticMatchResponse(job(12L), 0.7)));
        var result = (Map<?, ?>) tool.execute(
                json.readTree("{\"resumeId\":1,\"limit\":2,\"jobIds\":[10,11,12]}"),
                new AgentToolContext(new AppUser()));
        assertEquals(2, ((List<?>) result.get("matches")).size());
        assertEquals(false, result.containsKey("notInLibrary"));
    }

    private static JobDescriptionResponse job(long id) {
        return new JobDescriptionResponse(id, 1L, "Java 后端", "示例科技", "深圳", "实习", "在校生",
                "负责 Java 开发", "", "BOSS", "https://example.com/job", String.valueOf(id),
                null, null, "LISTING", null, null);
    }
}
