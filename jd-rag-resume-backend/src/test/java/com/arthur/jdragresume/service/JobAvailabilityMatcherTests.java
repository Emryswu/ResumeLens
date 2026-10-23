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
}
