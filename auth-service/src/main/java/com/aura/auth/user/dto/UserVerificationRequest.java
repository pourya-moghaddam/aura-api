package com.aura.auth.user.dto;

import jakarta.validation.constraints.NotBlank;

/** See {@link UserRegistrationRequest} for why phone has no format pattern here. */
public record UserVerificationRequest(

        @NotBlank(message = "Phone number is required")
        String phone,

        @NotBlank(message = "OTP code is required")
        String otpCode
) {
}