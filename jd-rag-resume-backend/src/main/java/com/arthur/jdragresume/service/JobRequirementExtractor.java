package com.arthur.jdragresume.service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic FULL-JD eligibility clauses; never guesses from a listing or a resume. */
public final class JobRequirementExtractor {
    public enum Mode { REQUIRED, PREFERRED, NOT_MENTIONED, UNRESTRICTED, UNKNOWN }

    /** Levels: junior college=1, bachelor=2, master=3, doctorate=4. */
    public record Requirement(Mode mode, Integer required, Integer preferred,
                              String requiredEvidence, String preferredEvidence,
                              Set<Integer> allowedGraduationYears, Set<Integer> preferredGraduationYears) {
        public Requirement(Mode mode, Integer required, Integer preferred,
                           String requiredEvidence, String preferredEvidence) {
            this(mode, required, preferred, requiredEvidence, preferredEvidence, Set.of(), Set.of());
        }

        static Requirement absent() { return new Requirement(Mode.NOT_MENTIONED, null, null, null, null); }
        static Requirement unrestricted() { return new Requirement(Mode.UNRESTRICTED, null, null, null, null); }
        static Requirement unknown(String evidence) { return new Requirement(Mode.UNKNOWN, null, null, evidence, null); }
    }
    public record Extraction(Requirement education, Requirement experience, Requirement graduation) { }

    private static final Pattern EDUCATION = Pattern.compile("博士|硕士|研究生|本科|大专|专科");
    private static final Pattern EDUCATION_CUE = Pattern.compile(
            "学历|学位|教育背景|本科及以上|硕士及以上|博士及以上|及以上|以上|不低于|"
            + "优先|加分|更佳|更好|均可|皆可|可接受|限|仅限|要求|必须|须|毕业|在读|应届");
    private static final Pattern EXPERIENCE_YEARS = Pattern.compile(
            "([0-9一二三四五六七八九十两]+)\\s*(?:[-~至]\\s*[0-9一二三四五六七八九十两]+\\s*)?年");
    private static final Pattern GRADUATION = Pattern.compile(
            "20\\d{2}(?:\\s*[、/，,或和及]\\s*20\\d{2})*\\s*届|20\\d{2}(?:\\s*[、/，,或和及]\\s*20\\d{2})*\\s*年毕业");
    private static final Pattern YEAR = Pattern.compile("20\\d{2}");
    private static final Pattern PREFERRED = Pattern.compile("优先|加分|更佳|更好|优待");
    private static final Pattern UNRESTRICTED_EDUCATION = Pattern.compile("学历不限|不限学历|无学历要求");
    private static final Pattern UNRESTRICTED_EXPERIENCE = Pattern.compile(
            "(?:工作)?经验(?:要求)?不限|不限(?:工作)?经验|无(?:工作)?经验要求|无需(?:工作)?经验|不要求(?:工作)?经验");
    private static final Pattern REQUIRED_WORDS = Pattern.compile("必须|必需|须|要求|不低于|至少|及以上|以上|起|限|仅限|需要|需");

    public static Extraction extract(String description, String requirements) {
        String full = (description == null ? "" : description) + "\n" + (requirements == null ? "" : requirements);
        return new Extraction(
                extractEducation(full),
                extractExperience(full),
                extractGraduation(full));
    }

    /**
     * Split independent requirements while preserving degree/year alternatives joined by "、" or "/".
     * A preferred clause after a comma must not soften the preceding mandatory clause.
     */
    private static String[] clauses(String full) {
        return full.split("[\\n。；;，,]+");
    }

    private static Requirement extractEducation(String full) {
        Integer required = null, preferred = null;
        String requiredText = null, preferredText = null, ambiguous = null;
        boolean unrestricted = false;
        for (String raw : clauses(full)) {
            String clause = raw.strip();
            if (clause.isEmpty()) continue;
            if (UNRESTRICTED_EDUCATION.matcher(clause).find()) {
                unrestricted = true;
                continue;
            }
            Matcher matcher = EDUCATION.matcher(clause);
            Integer lowest = null;
            while (matcher.find()) {
                // "与研究生团队合作" describes colleagues, not applicant qualifications.
                if (isTeamReference(clause, matcher.start(), matcher.end())) continue;
                int level = educationLevel(matcher.group());
                lowest = lowest == null ? level : Math.min(lowest, level);
            }
            if (lowest == null) {
                if ((clause.contains("学历") || clause.contains("学位"))
                        && (REQUIRED_WORDS.matcher(clause).find() || PREFERRED.matcher(clause).find()))
                    ambiguous = clause;
                continue;
            }
            // Mere mentions of students, e.g. "与研究生团队合作", cannot raise requirements.
            boolean context = EDUCATION_CUE.matcher(clause).find() || standaloneDegrees(clause);
            if (!context) continue;
            if (clause.contains("以下") || clause.contains("不高于")) {
                ambiguous = clause;
                continue;
            }
            if (PREFERRED.matcher(clause).find()) {
                if (preferred == null || lowest > preferred) {
                    preferred = lowest;
                    preferredText = clause;
                }
            } else {
                // Choices inside one clause use the lowest level. Different hard clauses must agree.
                if (required != null && !required.equals(lowest)) ambiguous = clause;
                if (required == null) {
                    required = lowest;
                    requiredText = clause;
                }
            }
        }
        if (ambiguous != null) return Requirement.unknown(ambiguous);
        if (required != null || preferred != null)
            return new Requirement(required != null ? Mode.REQUIRED : Mode.PREFERRED,
                    required, preferred, requiredText, preferredText);
        return unrestricted ? Requirement.unrestricted() : Requirement.absent();
    }

    private static boolean standaloneDegrees(String clause) {
        return clause.matches("(?:任职要求[:：]\\s*)?(?:博士|硕士|研究生|本科|大专|专科)"
                + "(?:\\s*[、/或和及]\\s*(?:博士|硕士|研究生|本科|大专|专科))*\\s*");
    }

    private static boolean isTeamReference(String clause, int start, int end) {
        String before = clause.substring(0, start);
        String after = clause.substring(end);
        return after.matches("^(?:生)?(?:团队|成员|同学|导师|学生).*")
                && (before.endsWith("与") || before.endsWith("和") || before.endsWith("同")
                    || before.endsWith("跟") || before.endsWith("协助") || before.endsWith("指导"));
    }

    private static int educationLevel(String value) {
        return switch (value) {
            case "专科", "大专" -> 1;
            case "本科" -> 2;
            case "硕士", "研究生" -> 3;
            case "博士" -> 4;
            default -> throw new IllegalArgumentException("Unknown education token: " + value);
        };
    }

    /**
     * Experience measured in years is a hard threshold only when the wording explicitly
     * refers to employment ("工作经验", "全职", "正式工作"). Project, development, or
     * generic relevant experience can be a different kind of evidence and stays UNKNOWN.
     */
    private static Requirement extractExperience(String full) {
        Integer required = null, preferred = null;
        String requiredText = null, preferredText = null, ambiguous = null;
        boolean unrestricted = false;
        for (String raw : clauses(full)) {
            String clause = raw.strip();
            if (clause.isEmpty()) continue;
            if (UNRESTRICTED_EXPERIENCE.matcher(clause).find()) {
                unrestricted = true;
                continue;
            }
            if (!clause.contains("经验") && !clause.contains("全职")
                    && !clause.contains("正式工作")) continue;

            boolean work = clause.contains("工作经验") || clause.contains("全职")
                    || clause.contains("正式工作");
            Matcher years = EXPERIENCE_YEARS.matcher(clause);
            Integer minimum = null;
            while (years.find()) {
                int parsed = number(years.group(1));
                if (parsed > 0) minimum = minimum == null ? parsed : Math.max(minimum, parsed);
            }
            if (!work || minimum == null) {
                ambiguous = clause;
                continue;
            }
            if (PREFERRED.matcher(clause).find()) {
                if (preferred == null || minimum > preferred) {
                    preferred = minimum;
                    preferredText = clause;
                }
            } else if (required == null || minimum > required) {
                required = minimum;
                requiredText = clause;
            }
        }
        if (ambiguous != null) return Requirement.unknown(ambiguous);
        if (required != null || preferred != null)
            return new Requirement(required != null ? Mode.REQUIRED : Mode.PREFERRED,
                    required, preferred, requiredText, preferredText);
        return unrestricted ? Requirement.unrestricted() : Requirement.absent();
    }

    private static Requirement extractGraduation(String full) {
        Set<Integer> requiredYears = new LinkedHashSet<>();
        Set<Integer> preferredYears = new LinkedHashSet<>();
        String requiredText = null, preferredText = null, ambiguous = null;
        for (String raw : full.split("[\\n。；;]+")) {
            String clause = raw.strip();
            if (clause.isEmpty()) continue;
            Matcher graduation = GRADUATION.matcher(clause);
            boolean found = false;
            while (graduation.find()) {
                found = true;
                Matcher year = YEAR.matcher(graduation.group());
                while (year.find()) {
                    int value = Integer.parseInt(year.group());
                    if (PREFERRED.matcher(clause).find()) {
                        preferredYears.add(value);
                        preferredText = clause;
                    } else {
                        requiredYears.add(value);
                        requiredText = clause;
                    }
                }
            }
            if (!found && (clause.contains("届") || clause.contains("毕业年份")
                    || clause.contains("毕业时间"))
                    && (REQUIRED_WORDS.matcher(clause).find() || PREFERRED.matcher(clause).find())) {
                ambiguous = clause;
            }
        }
        if (ambiguous != null) return Requirement.unknown(ambiguous);
        if (!requiredYears.isEmpty() || !preferredYears.isEmpty()) {
            return new Requirement(requiredYears.isEmpty() ? Mode.PREFERRED : Mode.REQUIRED,
                    requiredYears.size() == 1 ? requiredYears.iterator().next() : null,
                    preferredYears.size() == 1 ? preferredYears.iterator().next() : null,
                    requiredText, preferredText, Set.copyOf(requiredYears), Set.copyOf(preferredYears));
        }
        return Requirement.absent();
    }

    private static int number(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        if ("十".equals(value)) return 10;
        String digits = "零一二三四五六七八九";
        String normalized = value.replace("两", "二");
        int ten = normalized.indexOf('十');
        if (ten >= 0) {
            int high = ten == 0 ? 1 : digits.indexOf(normalized.charAt(0));
            int low = ten == normalized.length() - 1 ? 0 : digits.indexOf(normalized.charAt(ten + 1));
            return high >= 0 && low >= 0 ? high * 10 + low : -1;
        }
        return normalized.length() == 1 ? digits.indexOf(normalized.charAt(0)) : -1;
    }

    private JobRequirementExtractor() { }
}
