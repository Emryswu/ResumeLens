package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.dto.analysis.AnalysisHistoryResponse;
import com.arthur.jdragresume.service.AnalysisHistoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class GetLatestAnalysisTool implements AgentTool {
    private static final int TEXT_LIMIT = 600;

    private final AnalysisHistoryService analysisHistoryService;

    public GetLatestAnalysisTool(AnalysisHistoryService analysisHistoryService) {
        this.analysisHistoryService = analysisHistoryService;
    }

    @Override
    public String name() {
        return "get_latest_analysis";
    }

    @Override
    public String description() {
        return "读取某份简历与某个职位最近一次完整分析报告（状态、匹配分、优势、缺口、建议）。"
                + "status 为 PENDING 表示仍在生成，告诉用户稍后查看即可，不要反复调用本工具轮询。";
    }

    @Override
    public ToolSchema parameters() {
        return ToolSchema.object()
                .integer("resumeId", "简历 id", true, 1, Long.MAX_VALUE)
                .integer("jobId", "职位 id", true, 1, Long.MAX_VALUE);
    }

    @Override
    public Object execute(JsonNode arguments, AgentToolContext context) {
        return analysisHistoryService
                .findLatest(arguments.get("resumeId").asLong(), arguments.get("jobId").asLong())
                .<Object>map(GetLatestAnalysisTool::report)
                .orElse(Map.of("found", false));
    }

    private static Report report(AnalysisHistoryResponse analysis) {
        return new Report(
                true,
                analysis.id(),
                analysis.status().name(),
                analysis.matchScore(),
                ToolText.clip(analysis.summary(), TEXT_LIMIT),
                ToolText.clip(analysis.strengths(), TEXT_LIMIT),
                ToolText.clip(analysis.missingSkills(), TEXT_LIMIT),
                ToolText.clip(analysis.improvementSuggestions(), TEXT_LIMIT),
                analysis.updatedAt()
        );
    }

    record Report(
            boolean found,
            Long analysisId,
            String status,
            BigDecimal matchScore,
            String summary,
            String strengths,
            String missingSkills,
            String improvementSuggestions,
            LocalDateTime updatedAt
    ) {
    }
}
