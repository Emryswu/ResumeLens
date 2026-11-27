package com.arthur.jdragresume.controller;

import com.arthur.jdragresume.ai.LocalChatGptModels;
import com.arthur.jdragresume.common.ApiResponse;
import com.arthur.jdragresume.security.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/chatgpt")
public class LocalChatGptController {
    private final CurrentUserService users;
    private final LocalChatGptModels models;

    public LocalChatGptController(CurrentUserService users, LocalChatGptModels models) {
        this.users = users;
        this.models = models;
    }

    @GetMapping("/models")
    public ApiResponse<LocalChatGptModels.Catalog> catalog() {
        return ApiResponse.ok(models.catalog(users.getCurrentUser().getId()));
    }

    @PutMapping("/model")
    public ApiResponse<Void> select(@Valid @RequestBody Selection selection) {
        models.select(users.getCurrentUser().getId(), selection.slug());
        return ApiResponse.ok(null);
    }

    public record Selection(@NotBlank @Size(max = 128) String slug) {}
}
