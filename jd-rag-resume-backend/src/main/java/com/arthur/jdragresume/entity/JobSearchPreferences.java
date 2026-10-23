package com.arthur.jdragresume.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** One owned set of availability constraints per account. */
@Entity
@Table(name = "job_search_preferences")
public class JobSearchPreferences extends AuditableEntity {
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Column(name = "available_months")
    private Integer availableMonths;
    @Column(name = "days_per_week")
    private Integer daysPerWeek;
    @Column(name = "earliest_start_date")
    private LocalDate earliestStartDate;
    @Column(name = "preferred_district", length = 80)
    private String preferredDistrict;
    @Column(name = "district_required", nullable = false)
    private boolean districtRequired;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getAvailableMonths() { return availableMonths; }
    public void setAvailableMonths(Integer availableMonths) { this.availableMonths = availableMonths; }
    public Integer getDaysPerWeek() { return daysPerWeek; }
    public void setDaysPerWeek(Integer daysPerWeek) { this.daysPerWeek = daysPerWeek; }
    public LocalDate getEarliestStartDate() { return earliestStartDate; }
    public void setEarliestStartDate(LocalDate earliestStartDate) { this.earliestStartDate = earliestStartDate; }
    public String getPreferredDistrict() { return preferredDistrict; }
    public void setPreferredDistrict(String preferredDistrict) { this.preferredDistrict = preferredDistrict; }
    public boolean isDistrictRequired() { return districtRequired; }
    public void setDistrictRequired(boolean districtRequired) { this.districtRequired = districtRequired; }
}
