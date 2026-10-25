package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative eligibility hints. Ambiguous or missing JD clauses stay UNKNOWN. */
public final class JobAvailabilityMatcher {
    private static final Pattern MONTHS = Pattern.compile(
            "(?:实习(?:时长|期|时间)?\\s*(?:至少|不少于|不低于|需|要求)\\s*|(?:至少|不少于|不低于|连续)\\s*实习\\s*)(\\d{1,2})\\s*(?:个)?月");
    private static final Pattern DAYS = Pattern.compile(
            "(?:每周|一周|周)\\s*(?:至少|不少于|不低于|需|须|可|能|到岗)?\\s*([1-7])\\s*(?:天|日)");

    public record Finding(String status, String explanation) { }
    public record Comparison(Finding duration, Finding attendance, Finding startDate,
                             Finding district, Finding education, Finding experience, Finding graduation,
                             List<String> preferredWarnings, List<String> jdNotes,
                             List<String> missingUserFields, boolean hardBlock, boolean jdConditionsMet,
                             boolean hasUnknown) { }

    public static Comparison compare(JobSearchPreferencesView own, String captureDepth,
                                     String description, String requirements, String location) {
        boolean full = "FULL".equals(captureDepth);
        String text = (description == null ? "" : description) + "\n"
                + (requirements == null ? "" : requirements);

        Finding months = numberFinding(own.availableMonths(), full, text, MONTHS, "个月");
        Finding days = numberFinding(own.daysPerWeek(), full, text, DAYS, "天/周");
        Finding start = own.earliestStartDate() == null
                ? new Finding("NOT_SET", "尚未填写最早到岗日期")
                : new Finding("UNKNOWN", "你最早 " + own.earliestStartDate().format(DateTimeFormatter.ISO_DATE)
                    + " 可以到岗，需向招聘方确认其要求的到岗日期");

        Finding district;
        String preferred = own.preferredDistrict();
        if (preferred == null || preferred.isBlank()) {
            district = new Finding("NOT_SET", "尚未填写地区偏好");
        } else if (!hasDistrict(location)) {
            district = new Finding("UNKNOWN", "已填写地区 " + preferred + "，职位地点不足以核实具体区域");
        } else if (compactPlace(location).contains(compactPlace(preferred))) {
            district = new Finding(own.districtRequired() ? "MEETS" : "PREFERRED",
                    "岗位地点 " + location + " 符合你填写的地区 " + preferred);
        } else if (own.districtRequired()) {
            district = new Finding("BLOCKED", "你限定了 " + preferred + "，岗位位于 " + location);
        } else {
            district = new Finding("LOWER_PRIORITY", "岗位位于 " + location + "，不在你优先的 " + preferred + "；只是排序偏好");
        }
        var extracted = full ? JobRequirementExtractor.extract(description, requirements) : null;
        Finding education = qualification(own.educationLevel(), full,
                extracted == null ? null : extracted.education(), "学历", false);
        Finding experience = qualification(own.workExperienceYears(), full,
                extracted == null ? null : extracted.experience(), "经验", Boolean.TRUE.equals(own.currentlyStudent()));
        Finding graduation = graduation(own.expectedGraduationDate(), full,
                extracted == null ? null : extracted.graduation());
        List<String> warnings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        addQualificationDetails("学历", education, extracted == null ? null : extracted.education(),
                own.educationLevel(), warnings, notes, missing);
        addQualificationDetails("经验", experience, extracted == null ? null : extracted.experience(),
                own.workExperienceYears(), warnings, notes, missing);
        addQualificationDetails("毕业时间", graduation, extracted == null ? null : extracted.graduation(),
                own.expectedGraduationDate() == null ? null : own.expectedGraduationDate().getYear(),
                warnings, notes, missing);
        boolean blocked = "BLOCKED".equals(months.status()) || "BLOCKED".equals(days.status())
                || "BLOCKED".equals(district.status()) || "BLOCKED".equals(education.status())
                || "BLOCKED".equals(experience.status()) || "BLOCKED".equals(graduation.status());
        // Start date still needs HR confirmation; it cannot exclude an otherwise verified JD.
        boolean conditionsMet = full && !blocked
                && "MEETS".equals(months.status()) && "MEETS".equals(days.status())
                && (!own.districtRequired() || "MEETS".equals(district.status()))
                && qualificationEligible(education) && qualificationEligible(experience)
                && qualificationEligible(graduation);
        boolean hasUnknown = java.util.stream.Stream.of(months, days, district, education, experience, graduation)
                .anyMatch(finding -> "UNKNOWN".equals(finding.status()));
        return new Comparison(months, days, start, district, education, experience, graduation,
                List.copyOf(warnings), List.copyOf(notes), List.copyOf(missing),
                blocked, conditionsMet, hasUnknown);
    }

    private static boolean qualificationEligible(Finding value) {
        return switch (value.status()) {
            case "MEETS", "PREFERRED", "NOT_MENTIONED", "UNRESTRICTED" -> true;
            default -> false;
        };
    }

    private static Finding qualification(Integer userValue, boolean full,
                                         JobRequirementExtractor.Requirement requirement,
                                         String label, boolean currentlyStudent) {
        if (!full) return new Finding("UNKNOWN", "需获取 FULL JD 才能检查" + label);
        if (requirement.mode() == JobRequirementExtractor.Mode.NOT_MENTIONED)
            return new Finding("NOT_MENTIONED", "JD 未写" + label + "要求");
        if (requirement.mode() == JobRequirementExtractor.Mode.UNRESTRICTED)
            return new Finding("UNRESTRICTED", "JD 明确" + label + "不限");
        if (requirement.mode() == JobRequirementExtractor.Mode.UNKNOWN)
            return new Finding("UNKNOWN", "JD 的" + label + "条件无法可靠解析：" + requirement.requiredEvidence());
        if (userValue == null) {
            if ("经验".equals(label) && currentlyStudent && requirement.required() != null
                    && requirement.required() >= 3)
                return new Finding("BLOCKED", "你填写了在校生，岗位明确要求至少 "
                        + requirement.required() + " 年工作经验");
            return new Finding("UNKNOWN", "JD 写有" + label + "要求，但你尚未填写对应求职条件");
        }
        if (requirement.required() != null && userValue < requirement.required())
            return new Finding("BLOCKED", label + "低于 JD 明确门槛：" + requirement.requiredEvidence());
        if (requirement.preferred() != null && userValue < requirement.preferred())
            return new Finding("PREFERRED", "未满足 JD 的" + label + "优先条件：" + requirement.preferredEvidence());
        return new Finding("MEETS", label + "满足 JD 明确要求");
    }

    private static Finding graduation(java.time.LocalDate userDate, boolean full,
                                      JobRequirementExtractor.Requirement requirement) {
        if (!full) return new Finding("UNKNOWN", "需获取 FULL JD 才能检查毕业时间");
        if (requirement.mode() == JobRequirementExtractor.Mode.NOT_MENTIONED)
            return new Finding("NOT_MENTIONED", "JD 未写毕业届别要求");
        if (requirement.mode() == JobRequirementExtractor.Mode.UNKNOWN)
            return new Finding("UNKNOWN", "JD 毕业届别要求无法可靠解析：" + requirement.requiredEvidence());
        if (userDate == null) return new Finding("UNKNOWN", "JD 写有毕业时间要求，但尚未填写预计毕业时间");
        int year = userDate.getYear();
        if (requirement.required() != null && year != requirement.required())
            return new Finding("BLOCKED", "毕业届别不符：" + requirement.requiredEvidence());
        if (requirement.preferred() != null && year != requirement.preferred())
            return new Finding("PREFERRED", "未满足毕业届别偏好：" + requirement.preferredEvidence());
        return new Finding("MEETS", "毕业届别符合要求");
    }

    private static void addQualificationDetails(String label, Finding finding,
                                                JobRequirementExtractor.Requirement requirement,
                                                Integer actual, List<String> warnings, List<String> notes,
                                                List<String> missing) {
        if (requirement == null) return;
        if ("NOT_MENTIONED".equals(finding.status()) || "UNRESTRICTED".equals(finding.status())) {
            if (!"毕业时间".equals(label)) notes.add("JD 未写" + label + "要求");
        }
        if ("PREFERRED".equals(finding.status())) warnings.add(finding.explanation());
        if ("UNKNOWN".equals(finding.status()) && actual == null
                && (requirement.mode() == JobRequirementExtractor.Mode.REQUIRED
                    || requirement.mode() == JobRequirementExtractor.Mode.PREFERRED))
            missing.add(label);
    }

    /**
     * Stored locations come from the extension as {@code 城市·区·商圈} (see boss-api-extractor.js),
     * older ones as plain text such as {@code 深圳市龙岗区}. Compare without separators so
     * "深圳龙岗区", "龙岗区" and "龙岗" all match {@code 深圳·龙岗区·布吉}.
     */
    static String compactPlace(String place) {
        return place.replaceAll("[\\s·・•,，/|-]", "").replace("市", "");
    }

    private static boolean hasDistrict(String location) {
        if (location == null || location.isBlank()) return false;
        if (location.contains("·")) return location.split("·").length >= 2;
        return location.contains("区") || location.contains("县");
    }

    private static Finding numberFinding(Integer userValue, boolean full, String text,
                                         Pattern requirement, String unit) {
        if (userValue == null) return new Finding("NOT_SET", "你尚未填写 " + unit + " 的可用时间");
        if (!full) return new Finding("UNKNOWN", "职位尚为列表摘要，需获取完整 JD 才能核实 " + unit);
        List<Integer> minimums = new ArrayList<>();
        for (String sentence : text.split("[\\n。；;]")) {
            if (sentence.contains("优先")) continue;
            Matcher matcher = requirement.matcher(sentence);
            while (matcher.find()) minimums.add(Integer.parseInt(matcher.group(1)));
        }
        if (minimums.isEmpty()) return new Finding("UNKNOWN", "完整 JD 未明确可解析的 " + unit + " 要求，需向 HR 核实");
        int required = minimums.stream().mapToInt(Integer::intValue).max().orElseThrow();
        return userValue >= required
                ? new Finding("MEETS", "你可提供 " + userValue + unit + "，已识别岗位要求至少 " + required + unit)
                : new Finding("BLOCKED", "你可提供 " + userValue + unit + "，岗位明确要求至少 " + required + unit);
    }

    private JobAvailabilityMatcher() { }
}
