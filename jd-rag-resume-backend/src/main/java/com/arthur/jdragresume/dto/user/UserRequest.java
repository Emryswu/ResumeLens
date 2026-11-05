package com.arthur.jdragresume.dto.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Email @Size(max = 128) String email,
        @NotBlank @Size(max = 80) String displayName,
        @Size(min = 6, max = 72) String password,
        @Size(max = 72) String currentPassword
) {
}
