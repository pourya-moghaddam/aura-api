package com.aura.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The baseline rule, in one place because three separate paths create accounts — self sign-up, an
 * admin creating one, and the super-admin bootstrap — and they had already drifted apart once.
 */
class RolesTest {

    @Test
    @DisplayName("USER is added to whatever was asked for")
    void addsTheBaseline() {
        assertThat(Roles.withBaseline(Set.of(Roles.SELLER)))
            .containsExactlyInAnyOrder(Roles.SELLER, Roles.USER);
    }

    @Test
    @DisplayName("even the most privileged account gets it")
    void superAdminGetsItToo() {
        // Otherwise the one account guaranteed to exist cannot sign in to the storefront at all,
        // which makes it useless for exercising the buy path.
        assertThat(Roles.withBaseline(Set.of(Roles.SUPER_ADMIN)))
            .containsExactlyInAnyOrder(Roles.SUPER_ADMIN, Roles.USER);
    }

    @Test
    @DisplayName("asking for nothing still yields a usable account")
    void emptyRequestGivesUser() {
        assertThat(Roles.withBaseline(Set.of())).containsExactly(Roles.USER);
        assertThat(Roles.withBaseline(null)).containsExactly(Roles.USER);
    }

    @Test
    @DisplayName("asking for USER explicitly does not duplicate it")
    void idempotent() {
        assertThat(Roles.withBaseline(Set.of(Roles.USER))).containsExactly(Roles.USER);
    }

    @Test
    @DisplayName("the caller's set is not modified")
    void doesNotMutateTheInput() {
        // Callers pass a request DTO's collection, which may well be immutable.
        Set<String> requested = Set.of(Roles.ADMIN);

        assertThat(Roles.withBaseline(requested)).hasSize(2);
        assertThat(requested).containsExactly(Roles.ADMIN);
    }

    @Test
    @DisplayName("USER is not a control role, so the baseline grants no panel access")
    void baselineGrantsNoControlAccess() {
        // This is why adding it costs nothing: control endpoints are gated by the aud=control
        // audience plus a control role, never by the absence of USER.
        assertThat(Roles.isControlRole(Roles.USER)).isFalse();
        assertThat(Roles.CONTROL_ROLES)
            .containsExactlyInAnyOrder(Roles.SUPER_ADMIN, Roles.ADMIN, Roles.SELLER);
    }
}
