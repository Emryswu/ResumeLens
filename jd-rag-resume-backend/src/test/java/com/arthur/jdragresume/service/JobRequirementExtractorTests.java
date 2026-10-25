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

    @Test void ambiguousExplicitClauseIsUnknownRatherThanPassed() {
        var result = JobRequirementExtractor.extract("", "需有丰富项目经验");
        assertEquals(JobRequirementExtractor.Mode.UNKNOWN, result.experience().mode());
        assertEquals(JobRequirementExtractor.Mode.NOT_MENTIONED, result.education().mode());
    }
}
