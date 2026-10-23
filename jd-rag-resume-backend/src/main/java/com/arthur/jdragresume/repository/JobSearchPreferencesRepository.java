package com.arthur.jdragresume.repository;

import com.arthur.jdragresume.entity.JobSearchPreferences;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobSearchPreferencesRepository extends JpaRepository<JobSearchPreferences, Long> {
}
