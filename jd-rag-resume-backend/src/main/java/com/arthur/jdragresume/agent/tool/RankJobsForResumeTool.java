package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.service.JobSemanticMatchService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RankJobsForResumeTool implements AgentTool {
    private final JobSemanticMatchService jobSemanticMatchService;

    public RankJobsForResumeTool(JobSemanticMatchService jobSemanticMatchService) {
        this.jobSemanticMatchService = jobSemanticMatchService;
    }

    @Override
    public String name() {
        return "rank_jobs_for_resume";
    }

    @Override
    public String description() {
        return "用整篇向量相似度给职位库里的职位排序，返回与该简历最接近的前 N 个（jobId、职位名、similarity）。"
                + "传 jobIds 时只在这些职位里排序（例如刚用 collect_boss_jobs 采到的那批），不传则排全部职位。"
                + "similarity 是粗排用的语义相似度，不是匹配分，不能当成“匹配度百分比”告诉用户。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .integer("resumeId", "简历 id，来自 list_resumes", true, 1, Long.MAX_VALUE)
                .integer("limit", "返回条数，默认 5", false, 1, 10)
                .integerArray("jobIds", "只在这些职位 id 里排序，id 来自工具结果；不传则排全部", false, 1, Long.MAX_VALUE, 45);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        long resumeId = arguments.get("resumeId").asLong();
        int limit = arguments.path("limit").asInt(5);
        List<Long> jobIds = null;
        if (arguments.hasNonNull("jobIds")) {
            jobIds = new ArrayList<>();
            for (var id : arguments.get("jobIds")) jobIds.add(id.asLong());
        }
        List<JobSemanticMatchResponse> matches;
        try {
            matches = jobSemanticMatchService.rank(resumeId, limit, jobIds);
        } catch (BusinessException ex) {
            if (!"SEMANTIC_EMBEDDING_STALE".equals(ex.getCode())) {
                throw ex;
            }
            // Embeddings are a derived cache of the user's own texts; rebuilding them changes
            // nothing the user can see, so it does not need the confirmation gate.
            jobSemanticMatchService.refreshStaleEmbeddings(resumeId);
            matches = jobSemanticMatchService.rank(resumeId, limit, jobIds);
        }
        List<Item> items = matches.stream()
                .map(match -> new Item(
                        match.job().id(),
                        match.job().title(),
                        match.job().companyName(),
                        Math.round(match.similarity() * 10_000) / 10_000.0
                ))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumeId", resumeId);
        result.put("matches", items);
        if (jobIds != null) {
            List<Long> found = items.stream().map(Item::jobId).toList();
            // Only reported when the ids did not all rank: the model must not assume a missing job was compared.
            List<Long> missing = jobIds.stream().distinct().filter(id -> !found.contains(id)).toList();
            if (items.size() < limit && !missing.isEmpty()) result.put("notInLibrary", missing);
        }
        return result;
    }

    record Item(Long jobId, String title, String companyName, double similarity) {
    }
}
