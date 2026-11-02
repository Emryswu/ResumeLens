package com.arthur.jdragresume.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Each required group is one scoring unit: one skill, or any one of several alternatives. */
record HardSkillRequirements(List<List<String>> required, List<String> preferred) {
    private static final Pattern PREFERRED = Pattern.compile(
            "优先|加分|优选|更佳|更好|\\b(?:nice\\s+to\\s+have|preferred|a\\s+plus|bonus|optional)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRED = Pattern.compile(
            "必须|必需|必备|要求|需要|熟悉|熟练|精通|掌握|了解|\\b(?:required|must|proficient)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANY_ONE = Pattern.compile(
            "任意|任一|任选|至少(?:掌握|熟悉|精通|了解|具备)?一(?:种|项|门|个)|[二三四]选一|\\b(?:one|any)\\s+of\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OR = Pattern.compile(
            "\\s*(?:语言)?\\s*(?:或(?:者|是)?|or|/|／)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENUMERATION = Pattern.compile(
            "\\s*(?:[,、/／]|和|与|及|或(?:者|是)?|and|or)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLAUSE_SEPARATOR = Pattern.compile(
            "[，;；。!?！？]+|\\.(?=\\s|$)|(?<=required),|(?<=preferred),|,(?=\\s*(?:must\\b|required\\b|熟悉|掌握|了解|必须))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFERRED_HEADING = Pattern.compile(
            "^\\s*(?:加分项|优先技能|优先条件|优先项|nice\\s+to\\s+have|bonus|preferred(?:\\s+skills)?)(?:\\s*[:：]|\\s*$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REQUIRED_HEADING = Pattern.compile(
            "^\\s*(?:任职要求|岗位要求|必备技能|必须|requirements|required(?:\\s+skills)?)(?:\\s*[:：]|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    static HardSkillRequirements parse(List<String> fields, Map<String, List<String>> aliases) {
        Set<String> mandatory = new LinkedHashSet<>();
        Set<String> implicit = new LinkedHashSet<>();
        Set<String> preferred = new LinkedHashSet<>();
        Set<List<String>> alternatives = new LinkedHashSet<>();
        for (String field : fields) {
            boolean preferredSection = false;
            for (String line : field.split("\\R")) {
                if (PREFERRED_HEADING.matcher(line).find()) preferredSection = true;
                if (REQUIRED_HEADING.matcher(line).find()) preferredSection = false;
                // Chinese commas separate clauses; ASCII commas may enumerate skills within a clause.
                for (String clause : CLAUSE_SEPARATOR.split(line)) {
                    List<Mention> mentions = mentions(clause, aliases);
                    if (mentions.isEmpty()) continue;
                    if (preferredSection || PREFERRED.matcher(clause).find()) {
                        mentions.forEach(mention -> preferred.add(mention.skill()));
                        continue;
                    }
                    Pattern connector = ANY_ONE.matcher(clause).find() ? ENUMERATION : OR;
                    for (int start = 0; start < mentions.size();) {
                        int end = start + 1;
                        while (end < mentions.size() && connector.matcher(clause.substring(
                                mentions.get(end - 1).end(), mentions.get(end).start())).matches()) {
                            end++;
                        }
                        List<String> group = mentions.subList(start, end).stream().map(Mention::skill).toList();
                        if (group.size() > 1) {
                            alternatives.add(dictionaryOrder(group, aliases));
                        } else if (REQUIRED.matcher(clause).find()) {
                            mandatory.add(group.getFirst());
                        } else {
                            implicit.add(group.getFirst());
                        }
                        start = end;
                    }
                }
            }
        }
        // Explicit modalities in the JD take precedence over incidental mentions in a title/description.
        Set<String> alternativeMembers = new LinkedHashSet<>();
        alternatives.forEach(alternativeMembers::addAll);
        implicit.removeAll(preferred);
        implicit.removeAll(alternativeMembers);
        mandatory.addAll(implicit);
        preferred.removeAll(mandatory);
        List<List<String>> required = new ArrayList<>();
        for (String skill : aliases.keySet()) {
            if (mandatory.contains(skill)) required.add(List.of(skill));
            alternatives.stream().filter(group -> group.getFirst().equals(skill)).forEach(required::add);
        }
        return new HardSkillRequirements(List.copyOf(required), dictionaryOrder(preferred, aliases));
    }

    static String label(List<String> group) {
        return group.size() == 1 ? group.getFirst() : String.join(" / ", group) + "（任选其一）";
    }

    static boolean containsAlias(String text, List<String> aliases) {
        return aliases.stream().anyMatch(alias -> aliasPattern(alias).matcher(text).find());
    }

    private static List<Mention> mentions(String text, Map<String, List<String>> aliases) {
        List<Mention> mentions = new ArrayList<>();
        aliases.forEach((canonical, names) -> {
            for (String alias : names) {
                var matcher = aliasPattern(alias).matcher(text);
                while (matcher.find()) mentions.add(new Mention(canonical, matcher.start(), matcher.end()));
            }
        });
        // Aliases such as "vue" and "vue.js" may overlap; keep the longest mention at that position.
        mentions.sort(Comparator.comparingInt(Mention::start).thenComparing(
                Comparator.comparingInt(Mention::end).reversed()));
        List<Mention> result = new ArrayList<>();
        for (Mention mention : mentions) {
            if (result.isEmpty() || mention.start() >= result.getLast().end()) result.add(mention);
        }
        return result;
    }

    private static Pattern aliasPattern(String alias) {
        String normalized = alias.toLowerCase(Locale.ROOT);
        // ASCII boundaries allow skills adjacent to Chinese text without matching Java in JavaScript.
        String regex = normalized.chars().allMatch(ch -> ch < 128)
                ? "(?<![A-Za-z0-9])" + Pattern.quote(normalized) + "(?![A-Za-z0-9])"
                : Pattern.quote(normalized);
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private static List<String> dictionaryOrder(java.util.Collection<String> skills, Map<String, List<String>> aliases) {
        return aliases.keySet().stream().filter(skills::contains).toList();
    }

    private record Mention(String skill, int start, int end) {}
}
