package com.aura.auth.user.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * @param roles may be empty or omit {@code USER} entirely — every account gets {@code USER}
 *              unioned in regardless, the same way self-service sign-up does. Empty is a valid
 *              way to provision a plain user account by hand.
 */
public record CreateUserRequest(
    @NotBlank(message = "Phone number is required")
    String phone,

    Set<String> roles
) {
    public CreateUserRequest {
        if (roles == null) {
            roles = Set.of();
        }
    }
}
