package com.arthur.jdragresume.service;

import com.arthur.jdragresume.dto.user.JobSearchPreferencesInput;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.entity.JobSearchPreferences;
import com.arthur.jdragresume.exception.BusinessException;
import com.arthur.jdragresume.repository.JobSearchPreferencesRepository;
import com.arthur.jdragresume.security.CurrentUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobSearchPreferencesService {
    private final JobSearchPreferencesRepository repository;
    private final CurrentUserService currentUser;

    public JobSearchPreferencesService(JobSearchPreferencesRepository repository, CurrentUserService currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public JobSearchPreferencesView mine() {
        return forUser(currentUser.getCurrentUser().getId());
    }

    @Transactional(readOnly = true)
    public JobSearchPreferencesView forUser(Long userId) {
        return repository.findById(userId).map(JobSearchPreferencesView::from)
                .orElseGet(JobSearchPreferencesView::empty);
    }

    @Transactional
    public JobSearchPreferencesView save(JobSearchPreferencesInput input) {
        Long userId = currentUser.getCurrentUser().getId();
        String district = input.preferredDistrict() == null ? null : input.preferredDistrict().strip();
        if (input.districtRequired() && (district == null || district.isBlank())) {
            throw new BusinessException("JOB_PREFERENCES_INVALID", "限定地区前必须填写地区");
        }
        JobSearchPreferences stored = repository.findById(userId).orElseGet(() -> {
            JobSearchPreferences created = new JobSearchPreferences();
            created.setUserId(userId);
            return created;
        });
        stored.setAvailableMonths(input.availableMonths());
        stored.setDaysPerWeek(input.daysPerWeek());
        stored.setEarliestStartDate(input.earliestStartDate());
        stored.setPreferredDistrict(district == null || district.isEmpty() ? null : district);
        stored.setDistrictRequired(input.districtRequired());
        stored.setEducationLevel(input.educationLevel());
        stored.setExpectedGraduationDate(input.expectedGraduationDate());
        stored.setWorkExperienceYears(input.workExperienceYears());
        stored.setCurrentlyStudent(input.currentlyStudent());
        return JobSearchPreferencesView.from(repository.save(stored));
    }
}
