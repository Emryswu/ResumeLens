package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.analysis.AiAnalysisResult;
import com.arthur.jdragresume.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAnalysisServiceParsingTests {
    private final AnalysisResultParser parser = new AnalysisResultParser(new ObjectMapper());

    @Test
    void parsesStrictJsonResponse() {
        AiAnalysisResult result = parser.parse("""
                {
                  "matchScore": 82.5,
                  "strengths": "Java and Spring Boot [chunk-0]",
                  "missingSkills": "Deployment",
                  "improvementSuggestions": "Add delivery metrics",
                  "interviewQuestions": "Explain JWT",
                  "summary": "Good backend match"
                }
                """, Set.of(0));

        assertEquals(new BigDecimal("82.5"), result.matchScore());
        assertEquals("Java and Spring Boot [chunk-0]", result.strengths());
        assertEquals("Good backend match", result.summary());
    }

    @Test
    void normalizesPercentScoreArraysAndMarkdownFence() {
        AiAnalysisResult result = parser.parse("""
                ```json
                {
                  "matchScore": "75%",
                  "strengths": ["Java [chunk-0]", "MySQL [chunk-1]"],
                  "missingSkills": ["Testing", "Deployment"],
                  "improvementSuggestions": "Add evidence",
                  "interviewQuestions": ["Explain JWT", "Explain indexing"],
                  "summary": "Solid match"
                }
                ```
                """, Set.of(0, 1));

        assertEquals(new BigDecimal("75"), result.matchScore());
        assertEquals("Java [chunk-0]\nMySQL [chunk-1]", result.strengths());
        assertEquals("Testing\nDeployment", result.missingSkills());
        assertEquals("Explain JWT\nExplain indexing", result.interviewQuestions());
    }

    @Test
    void rejectsResponseWithoutScore() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse("{\"summary\":\"missing score\"}", Set.of())
        );

        assertEquals("AI_RESPONSE_PARSE_FAILED", exception.getCode());
    }

    @Test
    void tellsApartTheWaysAReplyCanFailToBeAnalysisJson() {
        assertFailure(AnalysisResultParser.ParseFailure.TRUNCATED_JSON,
                "{\"matchScore\": 80, \"strengths\": \"熟悉 Java [chunk-0]");
        assertFailure(AnalysisResultParser.ParseFailure.TRUNCATED_JSON, "```json\n{\"matchScore\": 80,");
        assertFailure(AnalysisResultParser.ParseFailure.TRUNCATED_JSON, "{\"matchScore\": 80");
        assertFailure(AnalysisResultParser.ParseFailure.MALFORMED_JSON, "抱歉，我无法完成这个请求。");
        assertFailure(AnalysisResultParser.ParseFailure.MALFORMED_JSON, "{\"matchScore\": 80,, \"summary\": \"x\"}");
        assertFailure(AnalysisResultParser.ParseFailure.NOT_AN_OBJECT, "[\"Java [chunk-0]\"]");
        assertFailure(AnalysisResultParser.ParseFailure.NOT_AN_OBJECT, "   ");
        assertFailure(AnalysisResultParser.ParseFailure.MISSING_SCORE, "{\"summary\":\"missing score\"}");
        assertFailure(AnalysisResultParser.ParseFailure.MISSING_SCORE, "{\"matchScore\": null}");
        assertFailure(AnalysisResultParser.ParseFailure.INVALID_SCORE, "{\"matchScore\": \"96分\"}");
        assertFailure(AnalysisResultParser.ParseFailure.INVALID_SCORE, "{\"matchScore\": \"高\"}");
    }

    private void assertFailure(AnalysisResultParser.ParseFailure expected, String reply) {
        AnalysisResultParser.ResponseParseException exception = assertThrows(
                AnalysisResultParser.ResponseParseException.class,
                () -> parser.parse(reply, Set.of(0)),
                reply
        );
        assertEquals(expected, exception.failure(), reply);
        // The refund path keys on this code; the new detail must not change it.
        assertEquals("AI_RESPONSE_PARSE_FAILED", exception.getCode());
    }

    @Test
    void rejectsStrengthWithoutCitation() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse(responseWithStrengths("Java and Spring Boot"), Set.of(0))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
    }

    @Test
    void rejectsCitationToFilteredOrMissingChunk() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse(responseWithStrengths("Java [chunk-2]"), Set.of(0, 1))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
    }

    @Test
    void rejectsMalformedCitationEvenWhenAnotherCitationIsValid() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> parser.parse(responseWithStrengths("Java [chunk-0] [chunk-x]"), Set.of(0))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
    }

    @Test
    void dropsOnlyTheStrengthsWhoseCitationsDoNotHoldAndKeepsTheRest() {
        AnalysisResultParser.ParsedAnalysis parsed = parser.parseWithAudit(
                responseWithStrengths("Java [chunk-0]\\nKafka [chunk-7]\\nMySQL [chunk-1]\\nRedis\\nDocker [chunk-0] [chunk-x]"),
                Set.of(0, 1)
        );

        assertEquals("Java [chunk-0]\nMySQL [chunk-1]", parsed.result().strengths());
        assertEquals("summary", parsed.result().summary());
        assertEquals(new BigDecimal("80"), parsed.result().matchScore());
        AnalysisResultParser.CitationAudit audit = parsed.audit();
        assertEquals(5, audit.strengths());
        assertEquals(3, audit.dropped());
        assertEquals(Set.of(7), audit.citedOutsideKept());
        assertEquals(1, audit.uncited());
        assertEquals(1, audit.malformed());
    }

    @Test
    void keepsTheModelTextUntouchedWhenEveryCitationIsValid() {
        AnalysisResultParser.ParsedAnalysis parsed = parser.parseWithAudit(
                responseWithStrengths("Java [chunk-0]；MySQL [chunk-1]"),
                Set.of(0, 1)
        );

        assertEquals("Java [chunk-0]；MySQL [chunk-1]", parsed.result().strengths());
        assertEquals(0, parsed.audit().dropped());
    }

    @Test
    void rejectsTheReportWhenEveryStrengthFailsAndReportsOnlyIds() {
        AnalysisResultParser.CitationRejectedException exception = assertThrows(
                AnalysisResultParser.CitationRejectedException.class,
                () -> parser.parseWithAudit(responseWithStrengths("Java [chunk-3]\\nMySQL [chunk-4]"), Set.of(0, 1))
        );

        assertEquals("AI_RESPONSE_CITATION_INVALID", exception.getCode());
        assertEquals(2, exception.audit().dropped());
        assertEquals(Set.of(3, 4), exception.audit().citedOutsideKept());
    }

    @Test
    void recordsHowUnrecognisedCitationsWereWrittenWithoutAnyResumeText() {
        AnalysisResultParser.CitationRejectedException exception = assertThrows(
                AnalysisResultParser.CitationRejectedException.class,
                () -> parser.parseWithAudit(responseWithStrengths(
                        "熟悉 Kafka [resume-chunk-12]\\n电话 13800001101 张三 (chunk 3 Zhang)\\n对账经验【证据 chunk-0】"),
                        Set.of(0, 12))
        );

        assertEquals(
                Set.of("[resume-chunk-N]", "(chunk N x)", "【** chunk-N】"),
                exception.audit().unrecognizedShapes()
        );
    }

    private String responseWithStrengths(String strengths) {
        return """
                {
                  "matchScore": 80,
                  "strengths": "%s",
                  "missingSkills": "",
                  "improvementSuggestions": "",
                  "interviewQuestions": "",
                  "summary": "summary"
                }
                """.formatted(strengths);
    }
}
