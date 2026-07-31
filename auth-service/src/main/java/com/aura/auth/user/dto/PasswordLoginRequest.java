package com.aura.auth.user.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordLoginRequest(

    @NotBlank(message = "Identifier (phone or email) is required")
    String identifier,

    @NotBlank(message = "Password cannot be blank")
    String password
) {
}