package com.arthur.jdragresume.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,64}$") String username,
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(max = 80) String displayName,
        @NotBlank @Size(min = 6, max = 72) String password
) {
    public RegisterRequest {
        if (username != null) username = username.trim();
        if (email != null) email = email.trim().toLowerCase(Locale.ROOT);
    }
}
