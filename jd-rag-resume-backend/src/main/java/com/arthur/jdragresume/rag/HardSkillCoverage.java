package com.arthur.jdragresume.rag;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public record HardSkillCoverage(
        List<String> required,
        List<String> matched,
        List<String> missing,
        List<String> preferred
) {
    public HardSkillCoverage(List<String> required, List<String> matched, List<String> missing) {
        this(required, matched, missing, List.of());
    }

    public HardSkillCoverage {
        required = required == null ? List.of() : List.copyOf(required);
        matched = matched == null ? List.of() : List.copyOf(matched);
        missing = missing == null ? List.of() : List.copyOf(missing);
        preferred = preferred == null ? List.of() : List.copyOf(preferred);
    }

    public boolean hasRules() {
        return !required.isEmpty();
    }

    public BigDecimal scoreCap() {
        if (!hasRules()) {
            return BigDecimal.valueOf(100).setScale(2);
        }
        double coverage = (double) matched.size() / required.size();
        return BigDecimal.valueOf(50.0 + 50.0 * coverage).setScale(2, RoundingMode.HALF_UP);
    }
}
