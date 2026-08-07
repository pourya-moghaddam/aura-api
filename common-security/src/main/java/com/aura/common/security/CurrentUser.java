package com.aura.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Reads the authenticated user out of the security context.
 *
 * <p>Replaces the pattern of injecting {@code Authentication} into controllers and casting the
 * principal by hand — which is where {@code Long.getLong(...)} crept in, silently returning null
 * because that method reads a system property rather than parsing a string.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<Long> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(authentication.getName()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * @throws IllegalStateException if there is no authenticated user — which means the endpoint
     *                               was left off the security config, not that the caller is anonymous
     */
    public static long requiredId() {
        return id().orElseThrow(() ->
            new IllegalStateException("No authenticated user in context; endpoint is not secured"));
    }

    public static boolean hasRole(String role) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        String authority = Roles.authority(role);
        return authentication.getAuthorities().stream()
            .anyMatch(granted -> authority.equals(granted.getAuthority()));
    }
}
