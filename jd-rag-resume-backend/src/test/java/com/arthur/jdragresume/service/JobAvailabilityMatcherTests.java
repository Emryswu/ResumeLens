package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class JobAvailabilityMatcherTests {
    private JobSearchPreferencesView profile(int months, int days, boolean requiredDistrict) {
        return new JobSearchPreferencesView(true, months, days, LocalDate.of(2026, 11, 1),
                "龙岗", requiredDistrict);
    }

    @Test void rejectsClearlyIncompatibleMonthsAndDays() {
        var compared = JobAvailabilityMatcher.compare(profile(3, 4, false), "FULL",
                "要求实习至少6个月；每周至少5天。", "", "深圳市龙岗区");
        assertEquals("BLOCKED", compared.duration().status());
        assertEquals("BLOCKED", compared.attendance().status());
        assertFalse(compared.jdConditionsMet());
        assertTrue(compared.hardBlock());
        assertEquals("PREFERRED", compared.district().status());
    }

    @Test void neverTreatsUnknownStartDateOrPreferenceAsHardFailure() {
        var compared = JobAvailabilityMatcher.compare(profile(6, 5, false), "FULL",
                "至少实习3个月；每周5天", "", "深圳市南山区");
        assertEquals("MEETS", compared.duration().status());
        assertEquals("MEETS", compared.attendance().status());
        assertEquals("LOWER_PRIORITY", compared.district().status());
        assertEquals("UNKNOWN", compared.startDate().status());
        assertFalse(compared.hardBlock());
        assertTrue(compared.jdConditionsMet(), "start date is an HR reminder, not a parsed JD condition");
    }

    @Test void anExplicitDistrictExclusionCanBlock() {
        var compared = JobAvailabilityMatcher.compare(profile(6, 5, true), "FULL",
                "实习至少3个月，每周5天。", "", "深圳市南山区");
        assertEquals("BLOCKED", compared.district().status());
        assertTrue(compared.hardBlock());
    }

    @Test void listingCannotBePromotedToVerifiedAvailability() {
        var compared = JobAvailabilityMatcher.compare(profile(6, 5, false), "LISTING",
                "至少实习3个月，每周5天。", "", "深圳市龙岗区");
        assertEquals("UNKNOWN", compared.duration().status());
        assertEquals("UNKNOWN", compared.attendance().status());
        assertFalse(compared.jdConditionsMet());
        assertFalse(compared.hardBlock());
    }

    @Test void emptyProfileCannotClaimFullyVerified() {
        var compared = JobAvailabilityMatcher.compare(JobSearchPreferencesView.empty(), "FULL",
                "至少实习3个月；每周5天。", "", "深圳市龙岗区");
        assertFalse(compared.hardBlock());
        assertFalse(compared.jdConditionsMet());
        assertEquals("NOT_SET", compared.duration().status());
    }

    @Test void vagueOrPreferredConditionsAreUnknown() {
        var compared = JobAvailabilityMatcher.compare(profile(2, 2, false), "FULL",
                "实习三个月优先；每周5天优先；其他面议", "", "");
        assertEquals("UNKNOWN", compared.duration().status());
        assertEquals("UNKNOWN", compared.attendance().status());
        assertFalse(compared.hardBlock());
    }

    /** Real extension format is 城市·区·商圈 (boss-api-extractor.js); the UI hint suggests "深圳龙岗区". */
    @Test void districtMatchesTheExtensionLocationFormatWhicheverWayItIsTyped() {
        for (String typed : new String[] {"深圳龙岗区", "深圳市龙岗区", "龙岗区", "龙岗"}) {
            var preference = new JobSearchPreferencesView(true, 6, 5, null, typed, true);
            var compared = JobAvailabilityMatcher.compare(preference, "FULL",
                    "实习至少3个月；每周5天。", "", "深圳·龙岗区·坂田");
            assertEquals("MEETS", compared.district().status(), typed);
            assertFalse(compared.hardBlock(), typed);
        }
        var elsewhere = JobAvailabilityMatcher.compare(new JobSearchPreferencesView(true, 6, 5, null, "深圳龙岗区", true),
                "FULL", "实习至少3个月；每周5天。", "", "深圳·南山区·科技园");
        assertEquals("BLOCKED", elsewhere.district().status());
        var cityOnly = JobAvailabilityMatcher.compare(new JobSearchPreferencesView(true, 6, 5, null, "龙岗", true),
                "FULL", "实习至少3个月；每周5天。", "", "深圳");
        assertEquals("UNKNOWN", cityOnly.district().status());
    }

    /** The start date is never parsed from the JD, so it must not make the flag unreachable. */
    @Test void conditionsMetIsReachableWhenEveryParsedRequirementIsSatisfied() {
        var compared = JobAvailabilityMatcher.compare(profile(6, 5, true), "FULL",
                "实习至少3个月；每周5天。", "", "深圳·龙岗区·布吉");
        assertEquals("UNKNOWN", compared.startDate().status());
        assertTrue(compared.jdConditionsMet());
        var preferenceOnly = JobAvailabilityMatcher.compare(profile(6, 5, false), "FULL",
                "实习至少3个月；每周5天。", "", "深圳·南山区·科技园");
        assertTrue(preferenceOnly.jdConditionsMet(), "a non-required district is only a ranking preference");
    }

    private JobSearchPreferencesView applicant(Integer education, Integer years, Boolean student, LocalDate graduation) {
        return new JobSearchPreferencesView(true, 6, 5, null, null, false,
                education, graduation, years, student);
    }

    private JobAvailabilityMatcher.Comparison eligibleJd(JobSearchPreferencesView own, String additional) {
        return JobAvailabilityMatcher.compare(own, "FULL",
                "要求实习至少3个月；每周5天；" + additional, "", "深圳");
    }

    @Test void mastersPreferredNeverBlocksBachelorAndRetainsWarning() {
        var result = eligibleJd(applicant(2, 0, true, LocalDate.of(2027, 6, 30)),
                "本科及以上；硕士优先");
        assertEquals("PREFERRED", result.education().status());
        assertFalse(result.hardBlock());
        assertTrue(result.jdConditionsMet());
        assertTrue(result.preferredWarnings().getFirst().contains("硕士优先"));
    }

    @Test void bachelorMinimumAcceptsBachelorButMastersMinimumBlocksIt() {
        var own = applicant(2, 0, true, LocalDate.of(2027, 6, 30));
        assertEquals("MEETS", eligibleJd(own, "本科及以上").education().status());
        var required = eligibleJd(own, "硕士及以上");
        assertEquals("BLOCKED", required.education().status());
        assertTrue(required.hardBlock());
    }

    @Test void threeYearsExperienceBlocksStudentAndExplicitUnlimitedPasses() {
        var student = applicant(2, null, true, LocalDate.of(2027, 6, 30));
        assertEquals("BLOCKED", eligibleJd(student, "3年以上工作经验").experience().status());
        var unlimited = eligibleJd(student, "工作经验不限");
        assertEquals("UNRESTRICTED", unlimited.experience().status());
        assertTrue(unlimited.jdConditionsMet());
    }

    @Test void missingJdEducationExperienceRequirementsPassAndAreExplained() {
        var result = eligibleJd(applicant(null, null, null, null), "参与 Java 后端开发");
        assertEquals("NOT_MENTIONED", result.education().status());
        assertEquals("NOT_MENTIONED", result.experience().status());
        assertTrue(result.jdConditionsMet());
        assertEquals(java.util.List.of("JD 未写学历要求", "JD 未写经验要求"), result.jdNotes());
        assertTrue(result.missingUserFields().isEmpty());
    }

    @Test void missingUserEducationStaysUnknownAndRequestsProfileInput() {
        var result = eligibleJd(applicant(null, null, null, null), "本科及以上");
        assertEquals("UNKNOWN", result.education().status());
        assertFalse(result.hardBlock());
        assertFalse(result.jdConditionsMet());
        assertTrue(result.missingUserFields().contains("学历"));
    }

    @Test void graduationRequirementUsesOnlyTheUsersExplicitGraduationDate() {
        var unknown = eligibleJd(applicant(2, null, true, null), "仅限2027届");
        assertEquals("UNKNOWN", unknown.graduation().status());
        assertTrue(unknown.missingUserFields().contains("毕业时间"));
        assertEquals("MEETS", eligibleJd(applicant(2, null, true, LocalDate.of(2027, 6, 30)),
                "仅限2027届").graduation().status());
    }

    @Test void unquantifiedProjectAndDevelopmentExperienceIsIgnored() {
        var own = applicant(2, 0, true, null);
        for (String jd : new String[] {
                "有 Spring Boot 项目经验优先", "有项目经验",
                "具备 Java 开发经验", "有相关经验优先"
        }) {
            var result = eligibleJd(own, jd);
            assertEquals("NOT_MENTIONED", result.experience().status(), jd);
            assertTrue(result.jdConditionsMet(), jd);
            assertFalse(result.hardBlock(), jd);
        }
    }


    @Test void bachelorMeetsAlternativeDegreesAndResearcherTeamIsNotARequirement() {
        var bachelor = applicant(2, 0, true, LocalDate.of(2026, 6, 30));
        for (String jd : new String[] { "本科、硕士均可", "本科/硕士" }) {
            var result = eligibleJd(bachelor, jd);
            assertEquals("MEETS", result.education().status(), jd);
            assertTrue(result.jdConditionsMet(), jd);
        }
        var context = JobAvailabilityMatcher.compare(bachelor, "FULL",
                "与研究生团队合作；要求实习至少3个月；每周5天", "本科及以上", "深圳");
        assertEquals("MEETS", context.education().status());
        assertFalse(context.hardBlock());
    }

    @Test void contradictoryEducationClausesDoNotBlockOrCountAsConfirmed() {
        var result = JobAvailabilityMatcher.compare(applicant(2, 0, true, null), "FULL",
                "本科及以上；要求实习至少3个月；每周5天", "硕士及以上", "深圳");
        assertEquals("UNKNOWN", result.education().status());
        assertFalse(result.hardBlock());
        assertFalse(result.jdConditionsMet());
        assertTrue(result.hasUnknown());
    }

    @Test void severalGraduationYearsAcceptEitherCohort() {
        var own = applicant(2, 0, true, LocalDate.of(2026, 6, 30));
        var result = eligibleJd(own, "2026、2027届均可");
        assertEquals("MEETS", result.graduation().status());
        assertTrue(result.jdConditionsMet());
    }

    @Test void quantifiedDevelopmentAndProjectExperienceOnlyAddsJdNotes() {
        var own = applicant(2, 0, true, LocalDate.of(2027, 6, 30));
        for (String jd : new String[] { "1年以上Java开发经验", "2年项目经验" }) {
            var result = eligibleJd(own, jd);
            assertEquals("NOT_MENTIONED", result.experience().status(), jd);
            assertFalse(result.hardBlock(), jd);
            assertTrue(result.jdConditionsMet(), jd);
            assertFalse(result.hasUnknown(), jd);
            assertTrue(result.jdNotes().stream().anyMatch(note ->
                    note.contains("需自行判断项目经历是否算数") && note.contains(jd)), jd);
            assertFalse(result.jdNotes().contains("JD 未写经验要求"), jd);
        }
    }

    @Test void quantifiedFormalWorkExperienceStillBlocksStudentsWithZeroYears() {
        var result = eligibleJd(applicant(2, 0, true, null), "3年以上工作经验");
        assertEquals("BLOCKED", result.experience().status());
        assertTrue(result.hardBlock());
        assertFalse(result.jdConditionsMet());
    }

}
