package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.common.PageResponse;
import com.arthur.jdragresume.dto.resume.ResumeRequest;
import com.arthur.jdragresume.dto.resume.ResumeResponse;
import com.arthur.jdragresume.service.ResumeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    private final ResumeService resumeService;

    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @GetMapping
    public ApiResponse<PageResponse<ResumeResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String keyword
    ) {
        return ApiResponse.ok(resumeService.findAll(page, size, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResumeResponse> findById(@PathVariable Long id) {
        return ApiResponse.ok(resumeService.findById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ResumeResponse> create(@Valid @RequestBody ResumeRequest request) {
        return ApiResponse.ok(resumeService.create(request));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ResumeResponse> upload(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam String candidateName,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email
    ) {
        return ApiResponse.ok(resumeService.upload(file, title, candidateName, phone, email));
    }

    @PutMapping("/{id}")
    public ApiResponse<ResumeResponse> update(@PathVariable Long id, @Valid @RequestBody ResumeRequest request) {
        return ApiResponse.ok(resumeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        resumeService.delete(id);
    }
}
