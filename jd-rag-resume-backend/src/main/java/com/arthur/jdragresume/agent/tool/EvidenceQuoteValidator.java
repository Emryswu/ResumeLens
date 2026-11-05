package com.arthur.jdragresume.agent.tool;

import com.arthur.jdragresume.service.PromptRedactor;

/** Compare citations with the redacted copy actually visible to the model, without changing stored texts. */
final class EvidenceQuoteValidator {
    private EvidenceQuoteValidator() { }

    static boolean contains(String storedText, String quote) {
        // Only the source is redacted: raw contact details quoted by a model must not bypass the visible-text rule.
        return normalize(PromptRedactor.redact(storedText)).contains(normalize(quote));
    }

    static String normalize(String text) {
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
