package com.aura.auth.user.dto;

import java.util.Set;

/**
 * Replaces a user's role set wholesale, not incrementally — a PUT, not a PATCH-with-a-diff. The
 * caller sends the roles the account should end up holding; {@code USER} is unioned in
 * automatically the same way it is on creation.
 */
public record UpdateUserRolesRequest(Set<String> roles) {
    public UpdateUserRolesRequest {
        if (roles == null) {
            roles = Set.of();
        }
    }
}
