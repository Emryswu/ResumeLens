package com.arthur.jdragresume.dto.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code strengths} and {@code missingSkills} are the plain-text form kept for the extension, the agent tool and
 * exports; {@code strengthItems} and {@code gapItems} are the same findings, structured and citation-checked per item.
 */
public record AiAnalysisResult(
        BigDecimal matchScore,
        String strengths,
        String missingSkills,
        String improvementSuggestions,
        String interviewQuestions,
        String summary,
        List<ReportItem> strengthItems,
        List<ReportItem> gapItems
) {
    public AiAnalysisResult {
        strengthItems = strengthItems == null ? List.of() : List.copyOf(strengthItems);
        gapItems = gapItems == null ? List.of() : List.copyOf(gapItems);
    }
}
