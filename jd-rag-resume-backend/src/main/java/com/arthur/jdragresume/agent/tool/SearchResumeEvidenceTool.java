package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.entity.JobDescription;
import com.arthur.jdragresume.entity.Resume;
import com.arthur.jdragresume.rag.RagProperties;
import com.arthur.jdragresume.rag.ResumeRagService;
import com.arthur.jdragresume.rag.RetrievedChunk;
import com.arthur.jdragresume.service.JobDescriptionService;
import com.arthur.jdragresume.service.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Takes a resume x JD pair, never free text: the 0.72 evidence threshold was calibrated on
 * JD-shaped queries, and an arbitrary question would be scored outside that calibration.
 */
@Component
public class SearchResumeEvidenceTool implements AgentTool {
    private static final int CHUNK_LIMIT = 600;

    private final ResumeService resumeService;
    private final JobDescriptionService jobDescriptionService;
    private final ResumeRagService resumeRagService;
    private final RagProperties ragProperties;

    public SearchResumeEvidenceTool(
            ResumeService resumeService,
            JobDescriptionService jobDescriptionService,
            ResumeRagService resumeRagService,
            RagProperties ragProperties
    ) {
        this.resumeService = resumeService;
        this.jobDescriptionService = jobDescriptionService;
        this.resumeRagService = resumeRagService;
        this.ragProperties = ragProperties;
    }

    @Override
    public String name() {
        return "search_resume_evidence";
    }

    @Override
    public String description() {
        return "用某个职位作为查询，在简历中检索能支撑匹配判断的原文段落（与正式分析同一条 RAG 链路）。"
                + "只返回语义相似度达到阈值的段落；kept 为空表示简历里没有足够相关的证据。"
                + "回答时引用这些段落的原文，不要编造简历里没有的经历。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .integer("resumeId", "简历 id", true, 1, Long.MAX_VALUE)
                .integer("jobId", "作为检索查询的职位 id", true, 1, Long.MAX_VALUE);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        Resume resume = resumeService.getEntityForCurrentUser(arguments.get("resumeId").asLong());
        JobDescription job = jobDescriptionService.getEntityForCurrentUser(arguments.get("jobId").asLong());
        List<RetrievedChunk> chunks = resumeRagService.retrieve(context.user(), resume, job);
        List<Evidence> kept = chunks.stream()
                .filter(RetrievedChunk::kept)
                .map(chunk -> new Evidence(
                        chunk.chunkIndex(),
                        chunk.section(),
                        Math.round(chunk.rawSimilarity() * 10_000) / 10_000.0,
                        ToolText.clip(chunk.content(), CHUNK_LIMIT)
                ))
                .toList();
        return new Result(
                resume.getId(),
                job.getId(),
                job.getTitle(),
                ragProperties.getMinSimilarity(),
                kept,
                chunks.size() - kept.size()
        );
    }

    record Evidence(int chunkIndex, String section, double rawSimilarity, String content) {
    }

    record Result(Long resumeId, Long jobId, String jobTitle, double threshold, List<Evidence> kept, int filteredCount) {
    }
}
