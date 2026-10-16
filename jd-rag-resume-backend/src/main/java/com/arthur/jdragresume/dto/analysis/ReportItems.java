package com.arthur.jdragresume.dto.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** JSON form of the structured strength and gap columns. Reports written before them hold null. */
public final class ReportItems {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ReportItem>> LIST = new TypeReference<>() {
    };

    private ReportItems() {
    }

    public static String write(List<ReportItem> items) {
        if (items == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(items);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("report items are not serializable", ex);
        }
    }

    /** Null for an old report or an unreadable value, so the client falls back to the legacy text. */
    public static List<ReportItem> read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, LIST);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    public static String legacyText(List<ReportItem> items) {
        return String.join("\n", items.stream().map(ReportItem::legacyLine).toList());
    }
}
