package com.arthur.jdragresume.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Deliberately narrow first-person declarations; no LLM extraction or inferred traits. */
final class ExplicitMemoryPolicy {
    private static final Map<String, Pattern> DECLARATIONS = Map.of(
            "target_city", Pattern.compile("^(我的目标城市是|我的意向城市是).+"),
            "target_role", Pattern.compile("^(我的目标方向是|我的求职方向是).+"),
            "weakness", Pattern.compile("^(我的短板是|我自认的短板是|我不擅长).+")
    );
    private static final Pattern NON_DECLARATION = Pattern.compile("[?？“”\"「」『』]|假如|假设|如果|比如|例如|举例|他说|她说|不要记|别记|忘记|删除|不再|不想|不是|是否|可能|也许|吗|呢");

    private ExplicitMemoryPolicy() {
    }

    static List<Fact> extract(String question) {
        if (NON_DECLARATION.matcher(question).find()) return List.of();
        List<Fact> facts = new ArrayList<>();
        for (String part : question.split("[，,。；;！!\\n]")) {
            String clause = part.strip();
            if (clause.isEmpty() || clause.length() > 500) continue;
            DECLARATIONS.forEach((category, pattern) -> {
                if (pattern.matcher(clause).matches()) facts.add(new Fact(category, clause));
            });
        }
        return List.copyOf(facts);
    }

    record Fact(String category, String content) {
    }
}
