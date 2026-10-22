package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.job.JobDescriptionResponse;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import com.arthur.jdragresume.collect.JobCollectionHistory;

@Component
public class SearchJobsTool implements AgentTool {
    private static final int PAGE_SIZE = 10;

    private final JobDescriptionService jobDescriptionService;

    private final JobCollectionHistory history;

    public SearchJobsTool(JobDescriptionService jobDescriptionService, JobCollectionHistory history) {
        this.jobDescriptionService = jobDescriptionService;
        this.history = history;
    }

    @Override
    public String name() {
        return "search_jobs";
    }

    @Override
    public String description() {
        return "按关键词在当前用户的职位库中查找职位（匹配职位名或公司名，每页 10 条），可同时按 employmentType 岗位类型筛选，返回 jobId、职位名、公司、地点、类型及最近采集条件 collectionExperience。"
                + "employmentType 是落库岗位标签；找实习应按 collectionExperience=在校生 筛选，不要把实习人群当作 BOSS 的求职类型；是否实习仍需核实岗位内容。"
                + "0 条结果会附带职位库总数、各类型数量和最近采集范围；只代表本地未找到，可能尚未采集，不代表不存在。只做字面过滤，不做语义排序；想知道哪些职位最适合某份简历请用 rank_jobs_for_resume。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .string("keyword", "职位名或公司名关键词，可省略", false, 100)
                .string("employmentType", "岗位类型，按库中类型精确匹配，如 全职、兼职；省略时不过滤。不要用标题关键词代替类型", false, 60)
                .string("collectionExperience", "按最近采集时所用的工作经验条件筛选，如 在校生、应届生、经验不限。找实习用在校生；省略时不过滤，历史未知记录不匹配具体条件", false, 10)
                .integer("page", "页码，从 0 开始，可省略", false, 0, 50);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        int page = arguments.path("page").asInt(0);
        PageResponse<JobDescriptionResponse> result = jobDescriptionService.findAll(page, PAGE_SIZE, ToolText.keyword(arguments), arguments.path("employmentType").asText(""),
                arguments.path("collectionExperience").asText(""));
        List<Item> items = result.content().stream()
                .map(job -> new Item(job.id(), job.title(), job.companyName(), job.location(), job.employmentType(), job.collectionExperience()))
                .toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("total", result.totalElements());
        output.put("page", result.page());
        output.put("totalPages", result.totalPages());
        output.put("jobs", items);
        if (items.isEmpty()) {
            var counts = jobDescriptionService.libraryCounts();
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("total", counts.total());
            scope.put("byEmploymentType", counts.byEmploymentType());
            scope.put("latestCollection", history.latest().orElse(null));
            scope.put("note", "仅搜索当前用户的本地职位库，未找到可能是尚未采集或筛选范围不匹配，不代表市场上不存在。latestCollection 为 null 表示没有记录到采集条件，不能据此声称从未采集。");
            output.put("libraryContext", scope);
        }
        return output;
    }

    record Item(Long jobId, String title, String companyName, String location, String employmentType, String collectionExperience) {
    }
}
