package com.arthur.jdragresume.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Returns verbatim JD excerpts for eligibility review, not inferred eligibility or a match score.
 * Only called on FULL postings. "Requirement" wording alone does not prove a hard exclusion.
 */
final class JobRequirementEvidence {
    private static final Pattern EDUCATION = Pattern.compile("学历|本科|硕士|研究生|专科|大专|博士|全日制|在读");
    private static final Pattern DURATION = Pattern.compile("实习.{0,14}(?:[0-9一二三四五六七八九十两]+\\s*(?:个)?月|半年|学期|[0-9一二三四五六]+周)|(?:至少|不少于|连续).{0,10}(?:个)?月|实习期");
    private static final Pattern ATTENDANCE = Pattern.compile("(?:每周|一周|每星期|周).{0,12}[1-7一二三四五六七两].{0,4}(?:天|日)|(?:到岗|出勤).{0,10}(?:天|日)");
    /**
     * Common technology words across backend, frontend, mobile, data, testing and AI postings. A
     * miss only means none of these words appears, so it is reported as such, not as "no stack".
     */
    private static final Pattern TECHNOLOGIES = Pattern.compile(
            "Java(?!Script)|Spring(?:\\s*Boot|\\s*Cloud|\\s*AI)?|MyBatis|MySQL|PostgreSQL|Oracle|SQL\\s*Server|SQL|Redis|MongoDB|Elasticsearch|"
            + "Kafka|RocketMQ|RabbitMQ|微服务|消息队列|分布式|"
            + "Python|Go(?:lang)?\\b|C\\+\\+|C#|\\.NET|Rust|PHP|Node(?:\\.js)?|"
            + "JavaScript|TypeScript|React|Vue|Angular|HTML|CSS|小程序|前端|"
            + "Android|iOS|Swift|Kotlin|Flutter|"
            + "Linux|Docker|Kubernetes|K8s|Git|"
            + "Hadoop|Spark|Flink|Hive|"
            + "Selenium|JMeter|Postman|自动化测试|"
            + "PyTorch|TensorFlow|LangChain|RAG|Agent|大模型|LLM|机器学习|深度学习",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESPONSIBILITIES = Pattern.compile("岗位职责|工作职责|工作内容|主要负责|参与开发|你将负责|职责描述");
    private static final Pattern REQUIREMENTS = Pattern.compile("必须|至少|要求|需要|优先|熟悉|掌握|具备|有.*经验");

    static Fields extract(String description, String requirements) {
        String full = String.join("\n", description == null ? "" : description, requirements == null ? "" : requirements);
        String[] fragments = full.split("[\\n。；;]+");
        List<String> education = matching(fragments, EDUCATION, false);
        List<String> duration = matching(fragments, DURATION, false);
        List<String> attendance = matching(fragments, ATTENDANCE, false);
        List<String> technologies = matching(fragments, TECHNOLOGIES, false);
        List<String> responsibilities = matching(fragments, RESPONSIBILITIES, true);
        List<String> constraints = matching(fragments, REQUIREMENTS, true);
        List<String> unknown = new ArrayList<>();
        if (education.isEmpty()) unknown.add("学历");
        if (duration.isEmpty()) unknown.add("实习时长");
        if (attendance.isEmpty()) unknown.add("每周到岗天数");
        if (technologies.isEmpty()) unknown.add("技术栈（未命中常见技术词，以正文为准）");
        if (responsibilities.isEmpty()) unknown.add("工作职责");
        if (constraints.isEmpty()) unknown.add("其他任职要求");
        return new Fields(education, duration, attendance, technologies, responsibilities, constraints, unknown);
    }

    private static List<String> matching(String[] fragments, Pattern pattern, boolean includeFollowing) {
        Set<String> evidence = new LinkedHashSet<>();
        for (int i = 0; i < fragments.length && evidence.size() < 5; i++) {
            String fragment = fragments[i].trim();
            if (!pattern.matcher(fragment).find()) continue;
            if (includeFollowing && fragment.length() <= 12 && i + 1 < fragments.length) {
                fragment += "：" + fragments[i + 1].trim();
            }
            evidence.add(fragment.length() > 220 ? fragment.substring(0, 220) + "…" : fragment);
        }
        return List.copyOf(evidence);
    }

    /** Lists contain exact excerpts from the captured JD; unknownFields is explicit missing evidence. */
    record Fields(List<String> education, List<String> internshipDuration, List<String> daysPerWeek,
                  List<String> technologies, List<String> responsibilities,
                  List<String> requirementStatements, List<String> unknownFields) { }

    private JobRequirementEvidence() { }
}
