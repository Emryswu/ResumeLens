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
        assertFalse(compared.fullyVerified());
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
        assertFalse(compared.fullyVerified());
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
        assertFalse(compared.fullyVerified());
        assertFalse(compared.hardBlock());
    }

    @Test void emptyProfileCannotClaimFullyVerified() {
        var compared = JobAvailabilityMatcher.compare(JobSearchPreferencesView.empty(), "FULL",
                "至少实习3个月；每周5天。", "", "深圳市龙岗区");
        assertFalse(compared.hardBlock());
        assertFalse(compared.fullyVerified());
        assertEquals("NOT_SET", compared.duration().status());
    }

    @Test void vagueOrPreferredConditionsAreUnknown() {
        var compared = JobAvailabilityMatcher.compare(profile(2, 2, false), "FULL",
                "实习三个月优先；每周5天优先；其他面议", "", "");
        assertEquals("UNKNOWN", compared.duration().status());
        assertEquals("UNKNOWN", compared.attendance().status());
        assertFalse(compared.hardBlock());
    }
}
