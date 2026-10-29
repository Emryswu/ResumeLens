package com.arthur.jdragresume.agent;

import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Persistent search filters; only explicit tool arguments mutate this conversation-local state.
 *
 * <p>Every field is in one of three states: unset/cleared ({@code null}, the default applies), or a value. A value the
 * model wrote is the model's; {@link #userConfirmed} lists the fields whose current value the user approved on a
 * confirmation card, which is the only way a value outranks a hard condition (see {@link SearchConditionPolicy}).
 * Tool arguments can never add to it: {@link #patch} only ever removes approvals.
 */
public record JobSearchContext(String city, String jobType, String keyword, String experience,
                               Long resumeId, boolean preferencesLoaded, List<Search> searched,
                               List<String> userConfirmed) {
    /** Fields a user approval can apply to; each has a hard rule in {@link SearchConditionPolicy}. */
    static final List<String> CONFIRMABLE = List.of("experience");

    public JobSearchContext {
        searched = List.copyOf(Objects.requireNonNullElse(searched, List.of()));
        userConfirmed = List.copyOf(Objects.requireNonNullElse(userConfirmed, List.<String>of()).stream()
                .filter(CONFIRMABLE::contains).distinct().toList());
    }

    public JobSearchContext(String city, String jobType, String keyword, String experience,
                            Long resumeId, boolean preferencesLoaded, List<Search> searched) {
        this(city, jobType, keyword, experience, resumeId, preferencesLoaded, searched, List.of());
    }

    public static JobSearchContext empty() {
        return new JobSearchContext(null, null, null, null, null, false, List.of(), List.of());
    }

    /** Records that the user approved the current value of {@code field}; called only after a confirmation card. */
    public JobSearchContext confirmedByUser(String field) {
        if (!CONFIRMABLE.contains(field)) throw new IllegalArgumentException("not a confirmable field: " + field);
        List<String> approved = new ArrayList<>(userConfirmed);
        if (!approved.contains(field)) approved.add(field);
        return new JobSearchContext(city, jobType, keyword, experience, resumeId, preferencesLoaded, searched, approved);
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
        // An approval covers one exact value: any change to it, or to the job type it was given for, drops it.
        List<String> approved = userConfirmed.stream().filter(name -> !"experience".equals(name)
                || Objects.equals(newExperience, experience) && Objects.equals(newType, jobType)).toList();
        return new JobSearchContext(field(fields, "city", city), newType,
                field(fields, "keyword", keyword), newExperience,
                newResume, preferencesLoaded, searched, approved);
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
        return new JobSearchContext(city, jobType, keyword, experience, resumeId, preferencesLoaded, history, userConfirmed);
    }

    public JobSearchContext selectedResume(long id) {
        return new JobSearchContext(city, jobType, keyword, experience, id, preferencesLoaded, searched, userConfirmed);
    }

    public JobSearchContext preferencesRead() {
        return new JobSearchContext(city, jobType, keyword, experience, resumeId, true, searched, userConfirmed);
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

    public record Search(String keyword, String city, String experience) {
        public Search {
            keyword = normalizeKeyword(keyword);
        }

        static boolean sameKeyword(String first, String second) {
            return normalizeKeyword(first).equals(normalizeKeyword(second));
        }

        private static String normalizeKeyword(String value) {
            return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
    }
}
