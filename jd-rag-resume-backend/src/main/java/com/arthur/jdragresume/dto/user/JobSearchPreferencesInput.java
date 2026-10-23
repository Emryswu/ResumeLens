package com.arthur.jdragresume.dto.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record JobSearchPreferencesInput(
        @Min(1) @Max(24) Integer availableMonths,
        @Min(1) @Max(7) Integer daysPerWeek,
        LocalDate earliestStartDate,
        @Size(max = 80) String preferredDistrict,
        boolean districtRequired
) {
}
