package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** Exact citation checks against stored resume text; shared by the tool and its tests. */
public final class ResumeEvidenceValidator {
    // Sample resume skill/project sentences fit here; a technology name or a whole section does not.
    public static final int MIN_QUOTE_CHARS = 12;
    public static final int MAX_QUOTE_CHARS = 160;
    public static final String CODE = "RESUME_EVIDENCE_INVALID";

    private ResumeEvidenceValidator() { }

    public static String validate(JsonNode evidence, String storedText) {
        if (!evidence.isArray() || evidence.isEmpty()) return "缺少简历证据，至少提交 1 条 resumeEvidence";
        for (int i = 0; i < evidence.size(); i++) {
            JsonNode item = evidence.get(i);
            String label = "第" + (i + 1) + "条简历引用";
            if (!item.path("quote").isTextual()) return label + "缺少 quote";
            String quote = normalize(item.path("quote").asText());
            int length = quote.codePointCount(0, quote.length());
            if (length < MIN_QUOTE_CHARS || length > MAX_QUOTE_CHARS) {
                return label + "长度须为 " + MIN_QUOTE_CHARS + "～" + MAX_QUOTE_CHARS + " 个非空白字符";
            }
            if (!EvidenceQuoteValidator.contains(storedText, item.path("quote").asText())) return label + "与当前简历存储原文对不上";
            if (!item.path("supports").isTextual() || item.path("supports").asText().isBlank()) {
                return label + "缺少对应的 JD 要求 supports";
            }
        }
        return null;
    }

    public static String normalize(String text) {
        return EvidenceQuoteValidator.normalize(text);
    }
}
