package com.aura.auth.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * No format pattern here on purpose. A generic E.164 regex rejects the way most users actually
 * type their own number — {@code 09121234567} starts with {@code 0}, which a strict {@code
 * [1-9]\d+} pattern refuses — and {@link com.aura.common.phone.IranianPhoneNumber} already accepts
 * and normalizes every real-world variant (Persian digits, 0-prefixed, spaced, etc.), rejecting
 * genuine garbage with its own 400. Duplicating that logic here as a stricter, wrong regex only
 * reintroduces the bug it was meant to prevent.
 */
public record UserRegistrationRequest(

        @NotBlank(message = "Phone number is required")
        String phone
) {
}