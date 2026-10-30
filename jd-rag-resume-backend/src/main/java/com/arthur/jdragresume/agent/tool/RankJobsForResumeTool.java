package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.agent.ConversationStore;
import com.arthur.jdragresume.agent.JdFreshness;
import com.arthur.jdragresume.agent.JobSearchContext;
import com.arthur.jdragresume.agent.VerifyPriority;
import com.arthur.jdragresume.dto.job.JobSemanticMatchResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import com.arthur.jdragresume.service.JobSemanticMatchService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RankJobsForResumeTool implements AgentTool {
    /** Candidates fetched before conflicts are moved back, so a conflicting job never displaces one that fits. */
    private static final int CANDIDATE_POOL = 200;
    private static final int SUGGESTED_VERIFICATIONS = 3;

    private final JobSemanticMatchService jobSemanticMatchService;
    private final JobSearchPreferencesService preferences;
    private final JdFreshness freshness;
    private final ConversationStore conversations;

    /** Similarity order only, without the user's conditions. */
    public RankJobsForResumeTool(JobSemanticMatchService jobSemanticMatchService) {
        this(jobSemanticMatchService, null, null, null);
    }

    @Autowired
    public RankJobsForResumeTool(JobSemanticMatchService jobSemanticMatchService, JobSearchPreferencesService preferences,
                                 JdFreshness freshness, ConversationStore conversations) {
        this.jobSemanticMatchService = jobSemanticMatchService;
        this.preferences = preferences;
        this.freshness = freshness;
        this.conversations = conversations;
    }

    @Override
    public String name() {
        return "rank_jobs_for_resume";
    }

    @Override
    public String description() {
        return "用整篇向量相似度给职位库里的职位排序，返回与该简历最接近的前 N 个（jobId、职位名、similarity）。"
                + "传 jobIds 时只在这些职位里排序（例如刚用 collect_boss_jobs 采到的那批），不传则排全部职位。"
                + "similarity 是粗排用的语义相似度，不是匹配分，不能当成“匹配度百分比”告诉用户。"
                + "现有数据已能判定与求职硬条件冲突的岗位（hardConflict）排在最后，不要核实它们；"
                + "suggestedVerify 是按顺序建议核实的岗位，jdStatus 为 EXPIRED 表示完整 JD 核实已过期、需重新核实。";
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
        boolean screened = preferences != null;
        int fetched = screened ? CANDIDATE_POOL : limit;
        List<Long> jobIds = null;
        if (arguments.hasNonNull("jobIds")) {
            jobIds = new ArrayList<>();
            for (var id : arguments.get("jobIds")) jobIds.add(id.asLong());
        }
        List<JobSemanticMatchResponse> matches;
        try {
            matches = jobSemanticMatchService.rank(resumeId, fetched, jobIds);
        } catch (BusinessException ex) {
            if (!"SEMANTIC_EMBEDDING_STALE".equals(ex.getCode())) {
                throw ex;
            }
            // Embeddings are a derived cache of the user's own texts; rebuilding them changes
            // nothing the user can see, so it does not need the confirmation gate.
            jobSemanticMatchService.refreshStaleEmbeddings(resumeId);
            matches = jobSemanticMatchService.rank(resumeId, fetched, jobIds);
        }
        JobSearchPreferencesView own = screened ? preferences.forUser(context.user().getId()) : null;
        JobSearchContext search = conversations == null || context.run() == null
                ? JobSearchContext.empty() : conversations.searchContext(context.run());
        List<Item> ranked = matches.stream()
                .map(match -> new Item(
                        match.job().id(),
                        match.job().title(),
                        match.job().companyName(),
                        Math.round(match.similarity() * 10_000) / 10_000.0,
                        screened ? freshness.of(match.job()).name() : null,
                        screened ? VerifyPriority.hardConflict(match.job(), own, search) : null,
                        screened && freshness.needsVerification(match.job())
                ))
                .toList();
        // Stable: similarity order is kept within the jobs that fit and within the conflicting ones.
        List<Item> items = java.util.stream.Stream.concat(
                        ranked.stream().filter(item -> item.hardConflict() == null),
                        ranked.stream().filter(item -> item.hardConflict() != null))
                .limit(limit)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumeId", resumeId);
        result.put("matches", items);
        if (screened) {
            result.put("suggestedVerify", items.stream()
                    .filter(item -> item.hardConflict() == null && item.needsVerification())
                    .limit(SUGGESTED_VERIFICATIONS).map(Item::jobId).toList());
        }
        if (jobIds != null) {
            List<Long> found = ranked.stream().map(Item::jobId).toList();
            // Only reported when the ids did not all rank: the model must not assume a missing job was compared.
            List<Long> missing = jobIds.stream().distinct().filter(id -> !found.contains(id)).toList();
            if (items.size() < limit && !missing.isEmpty()) result.put("notInLibrary", missing);
        }
        return result;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Item(Long jobId, String title, String companyName, double similarity, String jdStatus,
                String hardConflict, @JsonIgnore boolean needsVerification) {
    }
}
