package com.arthur.jdragresume.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobRequirementEvidenceTests {
    @Test
    void extractsVerbatimHardConditionEvidenceFromAFullyCapturedPosting() {
        var fields = JobRequirementEvidence.extract(
                "岗位职责：参与 Java Spring Boot 后端开发。\n"
                + "要求本科及以上学历，在校生。\n"
                + "实习至少 3 个月，每周 5 天到岗。\n"
                + "熟悉 MySQL、Redis，具备 Java 基础。",
                "优先有 Docker 使用经验");
        assertTrue(fields.education().stream().anyMatch(x -> x.contains("本科")));
        assertTrue(fields.internshipDuration().stream().anyMatch(x -> x.contains("3 个月")));
        assertTrue(fields.daysPerWeek().stream().anyMatch(x -> x.contains("5 天")));
        assertTrue(fields.technologies().stream().anyMatch(x -> x.contains("MySQL")));
        assertTrue(fields.responsibilities().stream().anyMatch(x -> x.contains("后端开发")));
        assertTrue(fields.requirementStatements().stream().anyMatch(x -> x.contains("本科")));
        assertTrue(fields.unknownFields().isEmpty());
    }

    @Test
    void absentInternshipConstraintsRemainUnknownInsteadOfBeingInferred() {
        var fields = JobRequirementEvidence.extract("负责 Java 系统维护", "");
        assertTrue(fields.unknownFields().contains("学历"));
        assertTrue(fields.unknownFields().contains("实习时长"));
        assertTrue(fields.unknownFields().contains("每周到岗天数"));
        assertTrue(fields.education().isEmpty());
        assertTrue(fields.internshipDuration().isEmpty());
        assertTrue(fields.daysPerWeek().isEmpty());
    }

    @Test
    void nonJavaPostingsAreNotReportedAsHavingNoTechStack() {
        var frontend = JobRequirementEvidence.extract("岗位职责：负责 H5 页面开发。熟悉 React、TypeScript", "");
        assertTrue(frontend.technologies().stream().anyMatch(x -> x.contains("React")));
        assertTrue(frontend.unknownFields().stream().noneMatch(x -> x.startsWith("技术栈")));
        var golang = JobRequirementEvidence.extract("负责服务端开发，使用 Go 语言", "");
        assertTrue(golang.unknownFields().stream().noneMatch(x -> x.startsWith("技术栈")));
    }

    @Test
    void aMissIsReportedAsNoCommonTechWordRatherThanNoStack() {
        var fields = JobRequirementEvidence.extract("负责客户沟通与资料整理", "");
        assertTrue(fields.unknownFields().stream().anyMatch(x -> x.startsWith("技术栈") && x.contains("以正文为准")));
    }
}
