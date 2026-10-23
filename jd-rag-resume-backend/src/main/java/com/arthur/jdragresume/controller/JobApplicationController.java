package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.service.JobApplicationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/job-applications")
public class JobApplicationController {
    private final JobApplicationService applications;

    public JobApplicationController(JobApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    public ApiResponse<List<JobApplicationService.View>> list() {
        return ApiResponse.ok(applications.list());
    }

    @GetMapping("/{jobId}")
    public ApiResponse<JobApplicationService.View> get(@PathVariable long jobId) {
        return ApiResponse.ok(applications.get(jobId));
    }

    @PutMapping("/{jobId}")
    public ApiResponse<JobApplicationService.View> save(@PathVariable long jobId,
            @Valid @RequestBody JobApplicationService.Update update) {
        return ApiResponse.ok(applications.save(jobId, update));
    }

    @DeleteMapping("/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@PathVariable long jobId) {
        applications.clear(jobId);
    }
}
