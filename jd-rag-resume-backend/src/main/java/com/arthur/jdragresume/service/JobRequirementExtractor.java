package com.arthur.jdragresume.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic FULL-JD eligibility clauses; never guesses from a listing or a resume. */
public final class JobRequirementExtractor {
    public enum Mode { REQUIRED, PREFERRED, NOT_MENTIONED, UNRESTRICTED, UNKNOWN }

    /** Levels: junior college=1, bachelor=2, master=3, doctorate=4. */
    public record Requirement(Mode mode, Integer required, Integer preferred,
                              String requiredEvidence, String preferredEvidence) {
        static Requirement absent() { return new Requirement(Mode.NOT_MENTIONED, null, null, null, null); }
        static Requirement unrestricted() { return new Requirement(Mode.UNRESTRICTED, null, null, null, null); }
        static Requirement unknown(String evidence) { return new Requirement(Mode.UNKNOWN, null, null, evidence, null); }
    }
    public record Extraction(Requirement education, Requirement experience, Requirement graduation) { }

    private static final Pattern EDUCATION = Pattern.compile("博士|硕士|研究生|本科|大专|专科");
    private static final Pattern EXPERIENCE = Pattern.compile(
            "(?:(?:至少|不少于|不低于|满|有|需|须|要求)?\\s*([0-9一二三四五六七八九十两]+)(?:\\s*[-~至]\\s*[0-9一二三四五六七八九十两]+)?\\s*(?:年|年以上|年及以上|年起)\\s*(?:的)?\\s*(?:(?:相关|正式|全职|工作|开发|项目|行业)\\s*){0,3}经验|(?:工作|开发|项目|相关)\\s*经验\\s*(?:至少|不少于|不低于)?\\s*([0-9一二三四五六七八九十两]+)\\s*(?:年|年以上|年及以上)?)");
    private static final Pattern GRADUATION = Pattern.compile("(20\\d{2})\\s*届|(?:20\\d{2})\\s*年毕业");
    private static final Pattern PREFERRED = Pattern.compile("优先|加分|更佳|更好|优待");
    private static final Pattern UNRESTRICTED_EDUCATION = Pattern.compile("学历不限|不限学历|无学历要求");
    private static final Pattern UNRESTRICTED_EXPERIENCE = Pattern.compile("(?:工作)?经验(?:要求)?不限|不限(?:工作)?经验|无(?:工作)?经验要求|无需(?:工作)?经验|不要求(?:工作)?经验");
    private static final Pattern REQUIRED_WORDS = Pattern.compile("必须|必需|须|要求|不低于|至少|及以上|以上|起|限|仅限|需要|需");

    public static Extraction extract(String description, String requirements) {
        String full = (description == null ? "" : description) + "\n" + (requirements == null ? "" : requirements);
        return new Extraction(
                extractDimension(full, EDUCATION, UNRESTRICTED_EDUCATION, JobRequirementExtractor::educationLevel),
                extractDimension(full, EXPERIENCE, UNRESTRICTED_EXPERIENCE, JobRequirementExtractor::experienceYears),
                extractDimension(full, GRADUATION, null, JobRequirementExtractor::graduationYear));
    }

    private interface ValueParser { int read(Matcher matcher); }

    private static Requirement extractDimension(String full, Pattern pattern, Pattern unrestricted, ValueParser parser) {
        Integer hard = null, soft = null;
        String hardEvidence = null, softEvidence = null, unparsed = null;
        boolean unlimited = false;
        for (String raw : full.split("[\n。；;，,]+")) {
            String clause = raw.strip();
            if (clause.isEmpty()) continue;
            if (unrestricted != null && unrestricted.matcher(clause).find()) {
                unlimited = true;
                continue;
            }
            if (pattern == EDUCATION && (clause.contains("以下") || clause.contains("不高于"))) {
                unparsed = clause;
                continue;
            }
            Matcher match = pattern.matcher(clause);
            boolean matched = false;
            while (match.find()) {
                matched = true;
                int value = parser.read(match);
                if (value <= 0) {
                    unparsed = clause;
                    continue;
                }
                if (PREFERRED.matcher(clause).find()) {
                    if (soft == null || value > soft) { soft = value; softEvidence = clause; }
                } else {
                    if (hard == null || value > hard) { hard = value; hardEvidence = clause; }
                }
            }
            if (!matched && mentionsDimension(clause, pattern)
                    && (REQUIRED_WORDS.matcher(clause).find() || PREFERRED.matcher(clause).find())) {
                unparsed = clause;
            }
        }
        if (unparsed != null) return Requirement.unknown(unparsed);
        if (hard != null || soft != null) {
            return new Requirement(hard != null ? Mode.REQUIRED : Mode.PREFERRED,
                    hard, soft, hardEvidence, softEvidence);
        }
        if (unlimited) return Requirement.unrestricted();
        return Requirement.absent();
    }

    private static boolean mentionsDimension(String clause, Pattern pattern) {
        if (pattern == EDUCATION) return clause.contains("学历") || clause.contains("学位");
        if (pattern == EXPERIENCE) return clause.contains("经验");
        return clause.contains("毕业") || clause.contains("届");
    }

    private static int educationLevel(Matcher matcher) {
        return switch (matcher.group()) {
            case "专科", "大专" -> 1;
            case "本科" -> 2;
            case "硕士", "研究生" -> 3;
            case "博士" -> 4;
            default -> -1;
        };
    }

    private static int experienceYears(Matcher matcher) {
        return number(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
    }

    private static int graduationYear(Matcher matcher) {
        String value = matcher.group();
        Matcher year = Pattern.compile("20\\d{2}").matcher(value);
        return year.find() ? Integer.parseInt(year.group()) : -1;
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
