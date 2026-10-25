package com.arthur.jdragresume.dto.user;

import com.arthur.jdragresume.entity.JobSearchPreferences;
import java.time.LocalDate;

public record JobSearchPreferencesView(
        boolean configured,
        Integer availableMonths,
        Integer daysPerWeek,
        LocalDate earliestStartDate,
        String preferredDistrict,
        boolean districtRequired,
        Integer educationLevel,
        LocalDate expectedGraduationDate,
        Integer workExperienceYears,
        Boolean currentlyStudent
) {
    public JobSearchPreferencesView(boolean configured, Integer availableMonths, Integer daysPerWeek,
                                    LocalDate earliestStartDate, String preferredDistrict, boolean districtRequired) {
        this(configured, availableMonths, daysPerWeek, earliestStartDate, preferredDistrict, districtRequired,
                null, null, null, null);
    }
    public static JobSearchPreferencesView empty() {
        return new JobSearchPreferencesView(false, null, null, null, null, false, null, null, null, null);
    }

    public static JobSearchPreferencesView from(JobSearchPreferences preferences) {
        return new JobSearchPreferencesView(true, preferences.getAvailableMonths(),
                preferences.getDaysPerWeek(), preferences.getEarliestStartDate(),
                preferences.getPreferredDistrict(), preferences.isDistrictRequired(),
                preferences.getEducationLevel(), preferences.getExpectedGraduationDate(),
                preferences.getWorkExperienceYears(), preferences.getCurrentlyStudent());
    }
}
