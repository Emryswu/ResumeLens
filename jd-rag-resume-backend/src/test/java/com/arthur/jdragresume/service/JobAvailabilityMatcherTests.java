package com.arthur.jdragresume.service;

import com.arthur.jdragresume.agent.tool.CheckJobPreferencesTool;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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
        assertEquals("UNKNOWN", compared.duration().status());
        assertEquals("UNKNOWN", compared.attendance().status());
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

    @Test void upperExperienceLimitsAcceptStudentsWithZeroYears() {
        for (String jd : new String[] { "1年以内工作经验", "工作经验3年以下",
                "工作经验不超过3年", "3年之内工作经验" }) {
            var result = eligibleJd(applicant(2, 0, true, null), jd);
            assertEquals("MEETS", result.experience().status(), jd);
            assertFalse(result.hardBlock(), jd);
            assertTrue(result.jdConditionsMet(), jd);
        }
    }

    @Test void preferredEducationWithoutAUserValueRemainsEligibleAndRequestsInput() {
        var result = eligibleJd(applicant(null, 0, true, null), "硕士优先");
        assertEquals("PREFERRED", result.education().status());
        assertFalse(result.hardBlock());
        assertTrue(result.jdConditionsMet());
        assertFalse(result.hasUnknown());
        assertTrue(result.missingUserFields().contains("学历"));
        assertTrue(result.preferredWarnings().getFirst().contains("硕士优先"));
    }

    @Test void upperExperienceLimitsAcceptTheEndpointAndBlockValuesAboveIt() {
        assertEquals("MEETS", eligibleJd(applicant(2, 3, false, null), "3年以下工作经验").experience().status());
        var above = eligibleJd(applicant(2, 4, false, null), "3年以下工作经验");
        assertEquals("BLOCKED", above.experience().status());
        assertTrue(above.hardBlock());
        assertFalse(above.jdConditionsMet());
    }

    @Test void experienceRangesCheckBothEndpoints() {
        for (int years : new int[] { 0, 1, 3, 4 }) {
            var result = eligibleJd(applicant(2, years, false, null), "1-3年工作经验");
            boolean within = years >= 1 && years <= 3;
            assertEquals(within ? "MEETS" : "BLOCKED", result.experience().status());
            assertEquals(within, result.jdConditionsMet());
        }
    }

    @Test void upperExperienceLimitWithMissingYearsDoesNotInferAStudentHardBlock() {
        var result = eligibleJd(applicant(2, null, true, null), "3年以内工作经验");
        assertEquals("UNKNOWN", result.experience().status());
        assertFalse(result.hardBlock());
        assertTrue(result.missingUserFields().contains("经验"));
    }

    @Test void preferredUpperExperienceLimitRemainsOptionalForMissingAndExcessYears() {
        for (Integer years : new Integer[] { null, 4 }) {
            var result = eligibleJd(applicant(2, years, false, null), "3年以内工作经验优先");
            assertEquals("PREFERRED", result.experience().status());
            assertFalse(result.hardBlock());
            assertTrue(result.jdConditionsMet());
            assertFalse(result.hasUnknown());
            assertEquals(years == null, result.missingUserFields().contains("经验"));
        }
    }

    private JobSearchPreferencesView person(Integer months, Integer days, Integer education,
                                            Integer years, Boolean student) {
        return new JobSearchPreferencesView(true, months, days, null, null, false,
                education, null, years, student);
    }

    /** Ordinary full-time postings omit internship length; a non-student must still qualify. */
    @Test void fullTimeApplicantMeetsOrdinaryJdThatOmitsInternshipDuration() {
        var result = JobAvailabilityMatcher.compare(person(null, null, 2, 3, false), "FULL",
                "负责 Java 后端服务开发，参与系统设计与编码。", "本科及以上；3年以上工作经验",
                "深圳·南山区·科技园");
        assertEquals("NOT_APPLICABLE", result.duration().status());
        assertEquals("NOT_APPLICABLE", result.attendance().status());
        assertEquals("MEETS", result.education().status());
        assertEquals("MEETS", result.experience().status());
        assertFalse(result.hardBlock());
        assertFalse(result.hasUnknown());
        assertTrue(result.jdConditionsMet());
    }

    /** A student who filled internship terms still qualifies when the JD never states them. */
    @Test void studentWithFilledInternshipTermsMeetsJdThatDoesNotMentionThem() {
        var result = JobAvailabilityMatcher.compare(person(6, 4, 2, 0, true), "FULL",
                "参与业务系统开发，完成需求迭代。", "本科及以上", "深圳");
        assertEquals("NOT_MENTIONED", result.duration().status());
        assertEquals("NOT_MENTIONED", result.attendance().status());
        assertFalse(result.hardBlock());
        assertFalse(result.hasUnknown());
        assertTrue(result.jdConditionsMet());
    }

    /** An explicit weekly-attendance conflict still blocks; silence is not the same as a match. */
    @Test void studentAskingForFourDaysDoesNotMeetAnExplicitFiveDayRequirement() {
        var result = JobAvailabilityMatcher.compare(person(6, 4, 2, 0, true), "FULL",
                "实习至少3个月；每周至少5天。", "本科及以上", "深圳");
        assertEquals("MEETS", result.duration().status());
        assertEquals("BLOCKED", result.attendance().status());
        assertTrue(result.hardBlock());
        assertFalse(result.jdConditionsMet());
    }

    /**
     * Education, experience, internship months and days per week share one satisfaction rule,
     * the same one {@link CheckJobPreferencesTool} describes: an unset field is not a limit,
     * JD silence or an explicit unlimited clause counts as met, and only an explicit conflict
     * (or an unresolvable UNKNOWN) keeps {@code jdConditionsMet} false.
     */
    @Test void educationExperienceMonthsAndDaysShareTheToolDescriptionRule() {
        record Sample(String dimension, String status, boolean counts,
                      JobSearchPreferencesView own, String description, String requirements) { }
        // Other dimensions are held in a state the old and new rules both accept, so a miss is this dimension.
        String internship = "实习至少3个月；每周5天。";
        List<Sample> samples = List.of(
                new Sample("education", "MEETS", true, person(6, 5, 2, 1, true),
                        internship, "本科及以上；1年以上工作经验"),
                new Sample("education", "PREFERRED", true, person(6, 5, 2, 1, true),
                        internship, "硕士优先；1年以上工作经验"),
                new Sample("education", "NOT_MENTIONED", true, person(6, 5, 2, 1, true),
                        internship, "1年以上工作经验"),
                new Sample("education", "UNRESTRICTED", true, person(6, 5, 2, 1, true),
                        internship, "学历不限；1年以上工作经验"),
                new Sample("education", "UNKNOWN", false, person(6, 5, null, 1, true),
                        internship, "本科及以上；1年以上工作经验"),
                new Sample("education", "BLOCKED", false, person(6, 5, 2, 1, true),
                        internship, "硕士及以上；1年以上工作经验"),
                new Sample("experience", "MEETS", true, person(6, 5, 2, 2, true),
                        internship, "本科及以上；1-3年工作经验"),
                new Sample("experience", "PREFERRED", true, person(6, 5, 2, 4, true),
                        internship, "本科及以上；3年以内工作经验优先"),
                new Sample("experience", "NOT_MENTIONED", true, person(6, 5, 2, 1, true),
                        internship, "本科及以上"),
                new Sample("experience", "UNRESTRICTED", true, person(6, 5, 2, null, true),
                        internship, "本科及以上；工作经验不限"),
                new Sample("experience", "UNKNOWN", false, person(6, 5, 2, null, true),
                        internship, "本科及以上；1年以上工作经验"),
                new Sample("experience", "BLOCKED", false, person(6, 5, 2, 0, true),
                        internship, "本科及以上；3年以上工作经验"),
                new Sample("months", "MEETS", true, person(6, 5, 2, 1, true),
                        "实习至少3个月；每周5天。", "本科及以上；1年以上工作经验"),
                new Sample("months", "NOT_MENTIONED", true, person(6, 5, 2, 1, true),
                        "每周5天。", "本科及以上；1年以上工作经验"),
                new Sample("months", "NOT_SET", true, person(null, 5, 2, 1, true),
                        "每周5天。", "本科及以上；1年以上工作经验"),
                new Sample("months", "UNKNOWN", false, person(null, 5, 2, 1, true),
                        "实习至少6个月；每周5天。", "本科及以上；1年以上工作经验"),
                new Sample("months", "BLOCKED", false, person(3, 5, 2, 1, true),
                        "实习至少6个月；每周5天。", "本科及以上；1年以上工作经验"),
                new Sample("months", "NOT_APPLICABLE", true, person(3, 4, 2, 3, false),
                        "实习至少6个月；每周至少5天。", "本科及以上；3年以上工作经验"),
                new Sample("days", "MEETS", true, person(6, 5, 2, 1, true),
                        "实习至少3个月；每周5天。", "本科及以上；1年以上工作经验"),
                new Sample("days", "NOT_MENTIONED", true, person(6, 4, 2, 1, true),
                        "实习至少3个月。", "本科及以上；1年以上工作经验"),
                new Sample("days", "NOT_SET", true, person(6, null, 2, 1, true),
                        "实习至少3个月。", "本科及以上；1年以上工作经验"),
                new Sample("days", "UNKNOWN", false, person(6, null, 2, 1, true),
                        "实习至少3个月；每周至少5天。", "本科及以上；1年以上工作经验"),
                new Sample("days", "BLOCKED", false, person(6, 4, 2, 1, true),
                        "实习至少3个月；每周至少5天。", "本科及以上；1年以上工作经验"),
                new Sample("days", "NOT_APPLICABLE", true, person(3, 4, 2, 3, false),
                        "实习至少6个月；每周至少5天。", "本科及以上；3年以上工作经验")
        );
        var rule = java.util.Map.of(
                "MEETS", true, "PREFERRED", true, "NOT_MENTIONED", true, "UNRESTRICTED", true,
                "NOT_SET", true, "NOT_APPLICABLE", true, "UNKNOWN", false, "BLOCKED", false);
        List<String> failures = new ArrayList<>();
        for (String dimension : List.of("education", "experience", "months", "days")) {
            for (var entry : rule.entrySet()) {
                boolean decided = JobAvailabilityMatcher.countsTowardJdConditions(entry.getKey());
                if (decided != entry.getValue()) {
                    failures.add(dimension + " " + entry.getKey() + " decided " + decided
                            + " but the tool description expects " + entry.getValue());
                }
            }
        }
        for (Sample sample : samples) {
            var result = JobAvailabilityMatcher.compare(sample.own(), "FULL",
                    sample.description(), sample.requirements(), "深圳");
            var finding = switch (sample.dimension()) {
                case "education" -> result.education();
                case "experience" -> result.experience();
                case "months" -> result.duration();
                case "days" -> result.attendance();
                default -> throw new IllegalStateException(sample.dimension());
            };
            if (!sample.status().equals(finding.status())) {
                failures.add(sample.dimension() + " expected status " + sample.status()
                        + " but was " + finding.status() + " (" + finding.explanation() + ")");
            }
            if (result.jdConditionsMet() != sample.counts()) {
                failures.add(sample.dimension() + " " + sample.status()
                        + " jdConditionsMet=" + result.jdConditionsMet() + " expected " + sample.counts());
            } else if (sample.counts() != rule.get(sample.status())) {
                failures.add(sample.dimension() + " " + sample.status()
                        + " disagrees with the shared rule");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        String description = new CheckJobPreferencesTool(null, null).description();
        assertTrue(description.contains("用户没设该项不构成限制"), description);
        assertTrue(description.contains("JD 未提及或明确不限计入合格"), description);
        assertTrue(description.contains("不把 UNKNOWN 计入已确认符合"), description);
        assertTrue(description.contains("非在校用户不检查实习月数和每周天数"), description);
    }

}
