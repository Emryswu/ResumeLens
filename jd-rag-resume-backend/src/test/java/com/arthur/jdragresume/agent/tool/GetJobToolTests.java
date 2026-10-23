package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.entity.AppUser;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetJobToolTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aLongVerifiedPostingIsClippedWhileItsHardConditionEvidenceStaysComplete() {
        var jobs = mock(JobDescriptionService.class);
        String body = "岗位职责：负责 Java 后端开发。\n要求本科及以上学历，每周 5 天到岗，实习至少 3 个月。\n"
                + "公司介绍与福利说明。".repeat(2_000);
        when(jobs.findById(17L)).thenReturn(new JobDescriptionResponse(17L, 1L, "Java 实习", "示例科技", "深圳",
                "实习", null, body, "本科", "BOSS", "https://www.zhipin.com/job_detail/abc.html", "abc",
                null, null, "FULL", null, null));
        var output = mapper.valueToTree(new GetJobTool(jobs)
                .execute(mapper.createObjectNode().put("jobId", 17), new AgentToolContext(new AppUser())));
        // Five verified postings at full length would flood the model context.
        assertTrue(output.path("description").asText().length() <= GetJobTool.TEXT_LIMIT + 1);
        assertTrue(output.path("evidence").path("daysPerWeek").toString().contains("5 天"));
        assertTrue(output.path("evidence").path("internshipDuration").toString().contains("3 个月"));
    }
}
