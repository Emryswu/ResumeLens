package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SearchJobsTool implements AgentTool {
    private static final int PAGE_SIZE = 10;

    private final JobDescriptionService jobDescriptionService;

    public SearchJobsTool(JobDescriptionService jobDescriptionService) {
        this.jobDescriptionService = jobDescriptionService;
    }

    @Override
    public String name() {
        return "search_jobs";
    }

    @Override
    public String description() {
        return "按关键词在当前用户的职位库中查找职位（匹配职位名或公司名，每页 10 条），返回 jobId、职位名、公司、地点。"
                + "只做字面过滤，不做语义排序；想知道哪些职位最适合某份简历请用 rank_jobs_for_resume。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .string("keyword", "职位名或公司名关键词，可省略", false, 100)
                .integer("page", "页码，从 0 开始，可省略", false, 0, 50);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        int page = arguments.path("page").asInt(0);
        PageResponse<JobDescriptionResponse> result = jobDescriptionService.findAll(page, PAGE_SIZE, ToolText.keyword(arguments));
        List<Item> items = result.content().stream()
                .map(job -> new Item(job.id(), job.title(), job.companyName(), job.location()))
                .toList();
        return Map.of(
                "total", result.totalElements(),
                "page", result.page(),
                "totalPages", result.totalPages(),
                "jobs", items
        );
    }

    record Item(Long jobId, String title, String companyName, String location) {
    }
}
