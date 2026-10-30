package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.arthur.jdragresume.service.PromptRedactor;

/** Exact citation checks against stored resume text; shared by the tool and its tests. */
public final class ResumeEvidenceValidator {
    // Sample resume skill/project sentences fit here; a technology name or a whole section does not.
    public static final int MIN_QUOTE_CHARS = 12;
    public static final int MAX_QUOTE_CHARS = 160;
    public static final String CODE = "RESUME_EVIDENCE_INVALID";

    private ResumeEvidenceValidator() { }

    public static String validate(JsonNode evidence, String storedText) {
        if (!evidence.isArray() || evidence.isEmpty()) return "缺少简历证据，至少提交 1 条 resumeEvidence";
        String original = normalize(PromptRedactor.redact(storedText));
        for (int i = 0; i < evidence.size(); i++) {
            JsonNode item = evidence.get(i);
            String label = "第" + (i + 1) + "条简历引用";
            if (!item.path("quote").isTextual()) return label + "缺少 quote";
            String quote = normalize(item.path("quote").asText());
            int length = quote.codePointCount(0, quote.length());
            if (length < MIN_QUOTE_CHARS || length > MAX_QUOTE_CHARS) {
                return label + "长度须为 " + MIN_QUOTE_CHARS + "～" + MAX_QUOTE_CHARS + " 个非空白字符";
            }
            if (!original.contains(quote)) return label + "与当前简历存储原文对不上";
            if (!item.path("supports").isTextual() || item.path("supports").asText().isBlank()) {
                return label + "缺少对应的 JD 要求 supports";
            }
        }
        return null;
    }

    public static String normalize(String text) {
        StringBuilder normalized = new StringBuilder();
        text.codePoints().forEach(c -> {
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return;
            if (c == 0x3002 || c == 0xFF61) c = '.';
            if (c == 0xFF62) c = 0x300C;
            if (c == 0xFF63) c = 0x300D;
            if (c == 0xFF64) c = 0x3001;
            if (c == 0xFF65) c = 0x30FB;
            // Fold only punctuation in the fullwidth ASCII range, never letters/numbers or compatibility glyphs.
            if (c >= 0xFF01 && c <= 0xFF5E && isPunctuation(c - 0xFEE0)) c -= 0xFEE0;
            normalized.appendCodePoint(c);
        });
        return normalized.toString();
    }

    private static boolean isPunctuation(int c) {
        return c >= '!' && c <= '/' || c >= ':' && c <= '@'
                || c >= '[' && c <= '`' || c >= '{' && c <= '~';
    }
}
