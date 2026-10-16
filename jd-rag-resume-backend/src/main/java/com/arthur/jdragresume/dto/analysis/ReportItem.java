package com.arthur.jdragresume.dto.analysis;

import java.util.List;

/**
 * One strength or gap of a report. {@code chunks} are kept resume chunk indexes, already checked against the
 * retrieval; for a gap the list may be empty. {@code source} tells model output apart from the hard-skill rules.
 */
public record ReportItem(
        String title,
        String detail,
        List<Integer> chunks,
        ReportCategory category,
        Source source
) {
    public enum Source {
        MODEL,
        RULE
    }

    public ReportItem {
        title = title == null ? "" : title;
        detail = detail == null ? "" : detail;
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        category = category == null ? ReportCategory.GENERAL : category;
        source = source == null ? Source.MODEL : source;
    }

    /** The plain-text line kept in the legacy columns read by the extension, the agent tool and exports. */
    public String legacyLine() {
        StringBuilder line = new StringBuilder(title);
        if (!detail.isBlank()) {
            line.append(title.isBlank() ? "" : "：").append(detail);
        }
        for (Integer chunk : chunks) {
            line.append(" [chunk-").append(chunk).append(']');
        }
        return line.toString();
    }
}
