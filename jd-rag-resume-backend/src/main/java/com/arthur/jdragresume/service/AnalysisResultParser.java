package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AnalysisResultParser {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[chunk-(\\d+)]");
    /** Any bracketed token mentioning "chunk", in ASCII or full-width brackets. */
    private static final Pattern CITATION_LIKE_PATTERN = Pattern.compile(
            "[\\[【(（][^\\[\\]【】()（）\\n]{0,40}?chunk[^\\[\\]【】()（）\\n]{0,40}?[\\]】)）]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHAPE_WORD_PATTERN = Pattern.compile("[A-Za-z]+");
    private static final Set<String> SHAPE_VOCABULARY = Set.of("chunk", "chunks", "resume", "evidence", "id", "and");
    private static final String SHAPE_PUNCTUATION = "[]【】()（）-_:：,，、|/ #";
    private static final int MAX_SHAPES = 5;
    private static final int MAX_SHAPE_LENGTH = 80;
    private final ObjectMapper objectMapper;

    public AnalysisResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AiAnalysisResult parse(String text, Set<Integer> keptChunkIndexes) {
        return parseWithAudit(text, keptChunkIndexes).result();
    }

    /**
     * Strengths whose citations do not hold up are dropped one by one instead of failing the whole report;
     * the analysis fails only when nothing citable is left. The audit carries chunk ids and counts only,
     * never resume text, so it can go into the server log.
     */
    ParsedAnalysis parseWithAudit(String text, Set<Integer> keptChunkIndexes) {
        AiAnalysisResult result;
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(text));
            result = new AiAnalysisResult(parseScore(root.path("matchScore")), normalize(root.path("strengths")),
                    normalize(root.path("missingSkills")), normalize(root.path("improvementSuggestions")),
                    normalize(root.path("interviewQuestions")), normalize(root.path("summary")));
        } catch (Exception ex) {
            throw new BusinessException("AI_RESPONSE_PARSE_FAILED", "AI response is not valid analysis JSON");
        }
        String strengths = result.strengths();
        if (strengths == null || strengths.isBlank()) {
            return new ParsedAnalysis(result, CitationAudit.EMPTY);
        }

        Set<Integer> allowed = keptChunkIndexes == null ? Set.of() : Set.copyOf(keptChunkIndexes);
        List<String> accepted = new ArrayList<>();
        Set<Integer> citedOutsideKept = new TreeSet<>();
        int total = 0;
        int uncited = 0;
        int malformed = 0;
        Set<String> shapes = new TreeSet<>();
        for (String item : strengths.split("\\R|[；;]")) {
            if (item.isBlank()) {
                continue;
            }
            total++;
            Matcher matcher = CITATION_PATTERN.matcher(item);
            boolean found = false;
            boolean outside = false;
            while (matcher.find()) {
                found = true;
                int chunkIndex = parseChunkIndex(matcher.group(1));
                if (!allowed.contains(chunkIndex)) {
                    outside = true;
                    citedOutsideKept.add(chunkIndex);
                }
            }
            boolean broken = matcher.replaceAll("").contains("[chunk-");
            if (!found) {
                uncited++;
            } else if (broken) {
                malformed++;
            }
            if (!found || broken) {
                collectCitationShapes(item, shapes);
            }
            if (found && !outside && !broken) {
                accepted.add(item.strip());
            }
        }

        CitationAudit audit = new CitationAudit(total, total - accepted.size(), citedOutsideKept, uncited, malformed,
                shapes);
        if (accepted.isEmpty()) {
            throw new CitationRejectedException(audit);
        }
        if (audit.dropped() == 0) {
            return new ParsedAnalysis(result, audit);
        }
        return new ParsedAnalysis(new AiAnalysisResult(result.matchScore(), String.join("\n", accepted),
                result.missingSkills(), result.improvementSuggestions(), result.interviewQuestions(),
                result.summary()), audit);
    }

    /**
     * Records how an unrecognised citation was written, with everything that could be resume text masked:
     * digits become N, any word other than the citation vocabulary becomes x, every other character *.
     * "[resume-chunk-12]" is logged as "[resume-chunk-N]"; a phone number or a name cannot survive this.
     */
    private static void collectCitationShapes(String item, Set<String> shapes) {
        Matcher matcher = CITATION_LIKE_PATTERN.matcher(item);
        while (matcher.find() && shapes.size() < MAX_SHAPES) {
            String token = matcher.group();
            if (token.length() > MAX_SHAPE_LENGTH) {
                continue;
            }
            StringBuilder shape = new StringBuilder();
            Matcher word = SHAPE_WORD_PATTERN.matcher(token);
            int last = 0;
            while (word.find()) {
                shape.append(maskShape(token.substring(last, word.start())));
                String lower = word.group().toLowerCase(Locale.ROOT);
                shape.append(SHAPE_VOCABULARY.contains(lower) ? lower : "x");
                last = word.end();
            }
            shape.append(maskShape(token.substring(last)));
            shapes.add(shape.toString());
        }
    }

    private static String maskShape(String text) {
        StringBuilder masked = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (Character.isDigit(cp)) {
                if (masked.isEmpty() || masked.charAt(masked.length() - 1) != 'N') masked.append('N');
            } else if (SHAPE_PUNCTUATION.indexOf(cp) >= 0) {
                masked.appendCodePoint(cp);
            } else {
                masked.append('*');
            }
        });
        return masked.toString();
    }

    private static int parseChunkIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            // More digits than an int holds: certainly not a kept chunk.
            return -1;
        }
    }

    record ParsedAnalysis(AiAnalysisResult result, CitationAudit audit) {
    }

    /**
     * Ids, counts and masked citation shapes only. {@code citedOutsideKept} holds -1 for an index too large to parse;
     * {@code unrecognizedShapes} shows how citations the parser did not accept were written, e.g. "[resume-chunk-N]".
     */
    record CitationAudit(
            int strengths,
            int dropped,
            Set<Integer> citedOutsideKept,
            int uncited,
            int malformed,
            Set<String> unrecognizedShapes
    ) {
        static final CitationAudit EMPTY = new CitationAudit(0, 0, Set.of(), 0, 0, Set.of());

        CitationAudit {
            citedOutsideKept = Collections.unmodifiableSet(new TreeSet<>(citedOutsideKept));
            unrecognizedShapes = Collections.unmodifiableSet(new TreeSet<>(unrecognizedShapes));
        }
    }

    /** Every strength failed the citation check, so there is no report left to show. */
    static final class CitationRejectedException extends BusinessException {
        private final CitationAudit audit;

        CitationRejectedException(CitationAudit audit) {
            super("AI_RESPONSE_CITATION_INVALID", "each strength must cite only kept resume chunks");
            this.audit = audit;
        }

        CitationAudit audit() {
            return audit;
        }
    }

    private BigDecimal parseScore(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        String value = node.asText("").replace("%", "").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("matchScore is missing");
        return new BigDecimal(value);
    }

    private String normalize(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return "";
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            StringJoiner joiner = new StringJoiner("\n");
            node.forEach(item -> joiner.add(item.isTextual() ? item.asText() : item.toString()));
            return joiner.toString();
        }
        return node.toString();
    }

    private String stripMarkdownFence(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        return trimmed.replaceFirst("(?is)^```(?:json)?\\s*", "")
                .replaceFirst("(?is)\\s*```$", "").trim();
    }
}
