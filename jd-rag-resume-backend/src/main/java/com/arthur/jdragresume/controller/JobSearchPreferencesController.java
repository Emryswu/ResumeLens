package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesInput;
import com.arthur.jdragresume.dto.user.JobSearchPreferencesView;
import com.arthur.jdragresume.service.JobSearchPreferencesService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-search/preferences")
public class JobSearchPreferencesController {
    private final JobSearchPreferencesService service;
    public JobSearchPreferencesController(JobSearchPreferencesService service) { this.service = service; }

    @GetMapping
    public ApiResponse<JobSearchPreferencesView> read() {
        return ApiResponse.ok(service.mine());
    }

    @PutMapping
    public ApiResponse<JobSearchPreferencesView> save(@Valid @RequestBody JobSearchPreferencesInput input) {
        return ApiResponse.ok(service.save(input));
    }
}
