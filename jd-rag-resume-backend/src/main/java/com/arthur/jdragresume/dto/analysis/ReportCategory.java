package com.arthur.jdragresume.dto.analysis;

import java.util.Locale;

/**
 * Fixed category of a strength or gap. Stored and sent as the English code; display names live in the frontend.
 * Anything the model writes outside this list becomes {@link #GENERAL}.
 */
public enum ReportCategory {
    TECH_STACK,
    PROJECT,
    ENGINEERING,
    DOMAIN,
    COLLABORATION,
    GENERAL;

    public static ReportCategory fromCode(String code) {
        if (code == null || code.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(code.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            return GENERAL;
        }
    }
}
