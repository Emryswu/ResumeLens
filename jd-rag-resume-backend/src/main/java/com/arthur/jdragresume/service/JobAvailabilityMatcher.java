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
                             Finding district, boolean hardBlock, boolean fullyVerified) { }

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
        } else if (location == null || location.isBlank() || !location.contains("区") && !location.contains("县")) {
            district = new Finding("UNKNOWN", "已填写地区 " + preferred + "，职位地点不足以核实具体区域");
        } else if (location.replace("市", "").contains(preferred.replace("市", ""))) {
            district = new Finding(own.districtRequired() ? "MEETS" : "PREFERRED",
                    "岗位地点 " + location + " 符合你填写的地区 " + preferred);
        } else if (own.districtRequired()) {
            district = new Finding("BLOCKED", "你限定了 " + preferred + "，岗位位于 " + location);
        } else {
            district = new Finding("LOWER_PRIORITY", "岗位位于 " + location + "，不在你优先的 " + preferred + "；只是排序偏好");
        }
        boolean blocked = "BLOCKED".equals(months.status()) || "BLOCKED".equals(days.status())
                || "BLOCKED".equals(district.status());
        boolean verified = full && own.configured() && !blocked
                && !"NOT_SET".equals(months.status()) && !"NOT_SET".equals(days.status())
                && !"NOT_SET".equals(start.status()) && !"NOT_SET".equals(district.status())
                && !"UNKNOWN".equals(months.status())
                && !"UNKNOWN".equals(days.status())
                && !"UNKNOWN".equals(start.status())
                && !"UNKNOWN".equals(district.status());
        return new Comparison(months, days, start, district, blocked, verified);
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
