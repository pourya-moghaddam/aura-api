package com.aura.auth.user.dto;

import java.time.OffsetDateTime;
import java.util.Set;

public record UserProfileResponse(
    Long id,
    String phone,
    String email,
    boolean isActive,
    boolean hasPasswordSet,
    Set<String> roles,
    OffsetDateTime createdAt
) {
}