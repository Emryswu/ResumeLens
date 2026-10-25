package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Persistent search filters; only explicit tool arguments mutate this conversation-local state. */
public record JobSearchContext(String city, String jobType, String keyword, String experience,
                               Long resumeId, boolean preferencesLoaded, List<Search> searched) {
    public JobSearchContext {
        searched = List.copyOf(Objects.requireNonNullElse(searched, List.of()));
    }

    public static JobSearchContext empty() {
        return new JobSearchContext(null, null, null, null, null, false, List.of());
    }

    /** Missing property inherits, explicit null/empty text clears. Changing job type resets experience. */
    public JobSearchContext patch(JsonNode fields) {
        String newType = field(fields, "jobType", jobType);
        if (newType != null && !List.of("实习", "全职", "不限").contains(newType)) {
            throw new BusinessException("INVALID_ARGUMENTS", "jobType 只能是实习、全职、不限或空");
        }
        String newExperience = fields.has("experience")
                ? field(fields, "experience", experience)
                : fields.has("jobType") && !Objects.equals(newType, jobType) ? null : experience;
        Long newResume = resumeId;
        if (fields.has("resumeId")) {
            JsonNode id = fields.get("resumeId");
            newResume = id.isNull() ? null : id.asLong();
        }
        return new JobSearchContext(field(fields, "city", city), newType,
                field(fields, "keyword", keyword), newExperience,
                newResume, preferencesLoaded, searched);
    }

    private static String field(JsonNode node, String name, String previous) {
        if (!node.has(name)) return previous;
        JsonNode value = node.get(name);
        if (value.isNull()) return null;
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    /** A real collector command, after inherited parameters are applied. */
    public ObjectNode effective(ObjectNode arguments) {
        JobSearchContext next = patch(arguments);
        ObjectNode effective = arguments.deepCopy();
        effective.put("city", Objects.requireNonNullElse(next.city(), "全国"));
        effective.put("keyword", Objects.requireNonNullElse(next.keyword(), ""));
        effective.put("experience", next.effectiveExperience());
        // jobType/resumeId are session metadata, not CollectRequest fields.
        return effective;
    }

    public String effectiveExperience() {
        if (experience != null) return experience;
        return "实习".equals(jobType) ? "在校生" : "不限";
    }

    public Search asSearch() {
        return new Search(Objects.requireNonNullElse(keyword, ""), Objects.requireNonNullElse(city, "全国"),
                effectiveExperience());
    }

    public boolean alreadySearched(Search search) {
        return searched.contains(search);
    }

    public JobSearchContext collected(Search search) {
        List<Search> history = new ArrayList<>(searched);
        if (!history.contains(search)) history.add(search);
        return new JobSearchContext(city, jobType, keyword, experience, resumeId, preferencesLoaded, history);
    }

    public JobSearchContext selectedResume(long id) {
        return new JobSearchContext(city, jobType, keyword, experience, id, preferencesLoaded, searched);
    }

    public JobSearchContext preferencesRead() {
        return new JobSearchContext(city, jobType, keyword, experience, resumeId, true, searched);
    }

    public boolean hasConditions() {
        return city != null || jobType != null || keyword != null || experience != null || resumeId != null || !searched.isEmpty();
    }

    /** One short line for ordinary chat, without job-search workflow instructions. */
    public String summary() {
        if (!hasConditions()) return "";
        return "\n本会话保存的岗位搜索条件（仅供上下文参考）：城市=" + Objects.requireNonNullElse(city, "不限")
                + "，类型=" + Objects.requireNonNullElse(jobType, "未指定")
                + "，方向=" + Objects.requireNonNullElse(keyword, "未指定")
                + "，经验=" + effectiveExperience()
                + "，简历=" + (resumeId == null ? "未选择" : resumeId.toString())
                + "，已执行搜索=" + searched.size() + " 组。";
    }

    public record Search(String keyword, String city, String experience) {}
}
