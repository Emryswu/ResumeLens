package com.arthur.jdragresume.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobRequirementExtractorTests {
    @Test void recognizesHardAndPreferredInTheSameJd() {
        var result = JobRequirementExtractor.extract("任职要求：本科及以上，硕士优先；3年以上相关工作经验", "");
        assertEquals(JobRequirementExtractor.Mode.REQUIRED, result.education().mode());
        assertEquals(2, result.education().required());
        assertEquals(3, result.education().preferred());
        assertEquals(JobRequirementExtractor.Mode.REQUIRED, result.experience().mode());
        assertEquals(3, result.experience().required());
        assertTrue(result.education().preferredEvidence().contains("优先"));
    }

    @Test void preferenceWordsNeverBecomeHardBlocks() {
        for (String wording : new String[] { "硕士优先", "硕士加分", "硕士更佳" }) {
            var result = JobRequirementExtractor.extract("", wording);
            assertEquals(JobRequirementExtractor.Mode.PREFERRED, result.education().mode(), wording);
            assertNull(result.education().required());
            assertEquals(3, result.education().preferred());
        }
    }

    @Test void missingAndExplicitUnlimitedAreDistinct() {
        var absent = JobRequirementExtractor.extract("负责 Java 服务端开发", null);
        assertEquals(JobRequirementExtractor.Mode.NOT_MENTIONED, absent.education().mode());
        assertEquals(JobRequirementExtractor.Mode.NOT_MENTIONED, absent.experience().mode());
        var unlimited = JobRequirementExtractor.extract("", "学历不限；经验不限");
        assertEquals(JobRequirementExtractor.Mode.UNRESTRICTED, unlimited.education().mode());
        assertEquals(JobRequirementExtractor.Mode.UNRESTRICTED, unlimited.experience().mode());
    }

    @Test void acceptsYearVariantsAndGraduationCohorts() {
        assertEquals(3, JobRequirementExtractor.extract("", "三年以上工作经验").experience().required());
        assertEquals(3, JobRequirementExtractor.extract("", "工作经验3年以上").experience().required());
        assertEquals(2027, JobRequirementExtractor.extract("", "仅限2027届毕业生").graduation().required());
    }

    @Test void unquantifiedTechnicalExperienceDoesNotCreateAWorkExperienceRequirement() {
        for (String wording : new String[] {
                "有 Spring Boot 项目经验优先", "有项目经验", "具备 Java 开发经验",
                "有相关经验优先", "需有丰富项目经验"
        }) {
            var result = JobRequirementExtractor.extract("", wording);
            assertEquals(JobRequirementExtractor.Mode.NOT_MENTIONED, result.experience().mode(), wording);
            assertTrue(result.experienceNotes().isEmpty(), wording);
        }
    }

    @Test void alternativeDegreesUseTheLowestThresholdInsideOneClause() {
        for (String wording : new String[] { "本科、硕士均可", "本科/硕士" }) {
            var education = JobRequirementExtractor.extract("", wording).education();
            assertEquals(JobRequirementExtractor.Mode.REQUIRED, education.mode(), wording);
            assertEquals(2, education.required(), wording);
        }
    }

    @Test void describingResearcherColleaguesDoesNotIncreaseEducationRequirement() {
        var result = JobRequirementExtractor.extract("与研究生团队合作；参与 Java 后端开发",
                "本科及以上");
        assertEquals(JobRequirementExtractor.Mode.REQUIRED, result.education().mode());
        assertEquals(2, result.education().required());
        assertEquals("本科及以上", result.education().requiredEvidence());
        assertEquals(JobRequirementExtractor.Mode.NOT_MENTIONED,
                JobRequirementExtractor.extract("与研究生团队合作", "").education().mode());
    }

    @Test void incompatibleHardEducationClausesAreUnknownRatherThanTakingMaximum() {
        var education = JobRequirementExtractor.extract("本科及以上", "硕士及以上").education();
        assertEquals(JobRequirementExtractor.Mode.UNKNOWN, education.mode());
        assertNull(education.required());
        assertTrue(education.requiredEvidence().contains("硕士及以上"));
    }

    @Test void multipleAcceptableGraduationYearsAreASet() {
        for (String wording : new String[] { "2026、2027届均可", "2026届、2027届均可" }) {
            var graduation = JobRequirementExtractor.extract("", wording).graduation();
            assertEquals(JobRequirementExtractor.Mode.REQUIRED, graduation.mode(), wording);
            assertEquals(java.util.Set.of(2026, 2027), graduation.allowedGraduationYears(), wording);
        }
    }

    @Test void quantifiedNonEmploymentExperienceProducesNotesWithoutAnEmploymentGate() {
        for (String wording : new String[] {
                "1年以上Java开发经验", "2年项目经验", "2年以上相关经验"
        }) {
            var extracted = JobRequirementExtractor.extract("", wording);
            assertEquals(JobRequirementExtractor.Mode.NOT_MENTIONED, extracted.experience().mode(), wording);
            assertEquals(1, extracted.experienceNotes().size(), wording);
            assertTrue(extracted.experienceNotes().getFirst().contains("需自行判断项目经历是否算数"), wording);
            assertTrue(extracted.experienceNotes().getFirst().contains(wording), wording);
        }
        assertTrue(JobRequirementExtractor.extract("", "有丰富开发经验").experienceNotes().isEmpty());
    }

    @Test void formalWorkExperienceRemainsAQuantifiedHardRequirement() {
        for (String wording : new String[] {
                "3年以上工作经验", "3年以上正式工作经验", "3年以上全职开发经验"
        }) {
            var extracted = JobRequirementExtractor.extract("", wording);
            assertEquals(JobRequirementExtractor.Mode.REQUIRED, extracted.experience().mode(), wording);
            assertEquals(3, extracted.experience().required(), wording);
            assertTrue(extracted.experienceNotes().isEmpty(), wording);
        }
    }

    @Test void technicalExperienceNotesDoNotOverrideFormalExperienceRequirements() {
        var extracted = JobRequirementExtractor.extract("", "2年项目经验；3年以上工作经验");
        assertEquals(JobRequirementExtractor.Mode.REQUIRED, extracted.experience().mode());
        assertEquals(3, extracted.experience().required());
        assertEquals(1, extracted.experienceNotes().size());
    }

    @Test void upperExperienceWordingSetsOnlyAnUpperBound() {
        for (String wording : new String[] { "3年以内工作经验", "工作经验3年以下",
                "工作经验不超过3年", "不超过3年工作经验", "3年之内工作经验" }) {
            var experience = JobRequirementExtractor.extract("", wording).experience();
            assertEquals(JobRequirementExtractor.Mode.REQUIRED, experience.mode(), wording);
            assertNull(experience.required(), wording);
            assertEquals(3, experience.requiredMaximum(), wording);
        }
    }

    @Test void experienceIntervalsRetainBothBoundsAndIntersectHardClauses() {
        var range = JobRequirementExtractor.extract("", "1-3年工作经验；工作经验不超过2年").experience();
        assertEquals(1, range.required());
        assertEquals(2, range.requiredMaximum());
        assertTrue(range.requiredEvidence().contains("1-3年工作经验"));
        assertTrue(range.requiredEvidence().contains("工作经验不超过2年"));
        var conflicting = JobRequirementExtractor.extract("", "3年以上工作经验；1年以内工作经验").experience();
        assertEquals(JobRequirementExtractor.Mode.UNKNOWN, conflicting.mode());
    }

    @Test void preferredUpperExperienceBoundDoesNotCreateAHardRequirement() {
        var range = JobRequirementExtractor.extract("", "3年以内工作经验优先").experience();
        assertEquals(JobRequirementExtractor.Mode.PREFERRED, range.mode());
        assertNull(range.required());
        assertNull(range.requiredMaximum());
        assertNull(range.preferred());
        assertEquals(3, range.preferredMaximum());
    }

}
