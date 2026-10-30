package com.arthur.jdragresume.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResumeEvidenceValidatorTests {
    private final ObjectMapper json = new ObjectMapper();
    private String problem(String quote, String original) {
        return ResumeEvidenceValidator.validate(json.valueToTree(List.of(Map.of(
                "quote", quote, "supports", "使用 Spring Boot 构建服务端 API"))), original);
    }

    @Test
    void exactQuoteAndOnlyWhitespaceOrFullwidthPunctuationDifferencesPass() {
        String original = "熟练使用 Spring Boot、MySQL 构建 REST API；负责接口设计（Java）。";
        assertNull(problem(original, original));
        assertNull(problem("熟练使用Spring\nBoot、MySQL\u00a0构建REST API；负责接口设计(Java)。", original));
        assertNull(problem("使用 Spring Boot, MySQL: 负责接口设计!", "使用 Spring Boot， MySQL：\n负责接口设计！"));
        assertNull(problem("使用 Spring Boot, MySQL. 负责接口设计.", "使用 Spring Boot，MySQL。负责接口设计｡"));
    }

    @Test
    void inventedCaseChangedOrCompatibilityFoldedTextDoesNotMatch() {
        assertNotNull(problem("负责 Kubernetes 集群架构设计与运维", "负责 Spring Boot 接口开发"));
        assertNotNull(problem("熟练使用 spring boot 构建 REST API", "熟练使用 Spring Boot 构建 REST API"));
        assertNotNull(problem("熟练使用 Java 构建 REST API", "熟练使用 Ｊａｖａ 构建 REST API"));
    }

    @Test
    void inclusiveNormalizedLengthBoundsRejectWordsAndWholeSections() {
        assertNotNull(problem("Java", "Java"));
        int min = ResumeEvidenceValidator.MIN_QUOTE_CHARS;
        int max = ResumeEvidenceValidator.MAX_QUOTE_CHARS;
        assertNotNull(problem("经".repeat(min - 1), "经".repeat(min)));
        assertNull(problem("经".repeat(min), "经".repeat(min)));
        assertNull(problem("经".repeat(max), "经".repeat(max)));
        assertNotNull(problem("经".repeat(max + 1), "经".repeat(max + 1)));
        assertNotNull(problem("J a v a\n", "Java"));
    }

    @Test
    void citationsMatchOnlyTheSameRedactedStoredTextTheModelCanSee() {
        String stored = "项目联系方式：13812345678，邮箱 arthur@example.com，负责 Spring Boot 接口开发。";
        assertNull(problem("项目联系方式：[手机号]，邮箱 [邮箱]，负责 Spring Boot 接口开发。", stored));
        assertNotNull(problem(stored, stored));
        assertNotNull(problem("项目联系方式：13812345678，邮箱 [邮箱]，负责 Spring Boot 接口开发。", stored));
        assertNotNull(problem("项目联系方式：[手机号]，邮箱 arthur@example.com，负责 Spring Boot 接口开发。", stored));
    }

    @Test
    void missingEvidenceAndMissingSupportsAreRejected() {
        assertNotNull(ResumeEvidenceValidator.validate(json.createArrayNode(), "简历正文"));
        assertNotNull(ResumeEvidenceValidator.validate(json.missingNode(), "简历正文"));
        String quote = "熟练使用 Spring Boot 构建 REST API";
        assertNotNull(ResumeEvidenceValidator.validate(json.valueToTree(List.of(Map.of("quote", quote))), quote));
    }
}
