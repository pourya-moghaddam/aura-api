package com.aura.auth.user.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param active boxed and required rather than a primitive {@code boolean}. A primitive would make
 *               Jackson fail to construct the record when the property is absent, surfacing as an
 *               opaque 400 "Failed to read request"; and this flag is the entire payload, so
 *               defaulting it would quietly enable or disable an account the caller never named.
 *               {@code @NotNull} makes an omission a 400 that says so.
 */
public record UpdateUserStatusRequest(
    @NotNull(message = "Active flag is required")
    Boolean active
) {
}
