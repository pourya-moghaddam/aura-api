package com.aura.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the caller out of the security context.
 *
 * <p>Every seller-ownership check in catalog starts here, so an id read wrongly is an
 * authorization bug rather than an inconvenience. The predecessor of this class used
 * {@code Long.getLong(...)}, which reads a <em>system property</em> rather than parsing a string —
 * it returned null for every request and took out {@code /me} and password changes entirely.
 */
class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String principalName, String... roles) {
        var authorities = java.util.Arrays.stream(roles)
            .map(role -> new SimpleGrantedAuthority(Roles.authority(role)))
            .toList();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principalName, "n/a", authorities));
    }

    @Test
    @DisplayName("the principal name is parsed as the user id")
    void parsesTheUserId() {
        authenticate("42", Roles.USER);

        assertThat(CurrentUser.id()).contains(42L);
        assertThat(CurrentUser.requiredId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("no authentication means no id")
    void noAuthenticationYieldsEmpty() {
        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    @DisplayName("requiredId fails loudly rather than returning something wrong")
    void requiredIdThrowsWhenAbsent() {
        // The message points at the real cause: an endpoint left out of the security config,
        // not an anonymous caller who should have been rejected earlier.
        assertThatThrownBy(CurrentUser::requiredId)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not secured");
    }

    @Test
    @DisplayName("a non-numeric principal is empty, not an exception")
    void nonNumericPrincipalIsEmpty() {
        authenticate("anonymousUser", Roles.USER);

        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    @DisplayName("an anonymous token carries no id")
    void anonymousHasNoId() {
        SecurityContextHolder.getContext().setAuthentication(
            new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    @DisplayName("hasRole matches the ROLE_-prefixed authority")
    void hasRoleMatchesPrefixedAuthority() {
        authenticate("42", Roles.ADMIN);

        assertThat(CurrentUser.hasRole(Roles.ADMIN)).isTrue();
        assertThat(CurrentUser.hasRole(Roles.SELLER)).isFalse();
    }

    @Test
    @DisplayName("hasRole is false with no authentication rather than throwing")
    void hasRoleWithoutAuthentication() {
        assertThat(CurrentUser.hasRole(Roles.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("one role does not imply another")
    void rolesAreNotHierarchical() {
        // There is no role hierarchy configured; a SUPER_ADMIN is not automatically an ADMIN, and
        // code that assumes otherwise would grant less than it expects, not more.
        authenticate("42", Roles.SUPER_ADMIN);

        assertThat(CurrentUser.hasRole(Roles.SUPER_ADMIN)).isTrue();
        assertThat(CurrentUser.hasRole(Roles.ADMIN)).isFalse();
    }

    @Test
    @DisplayName("blacklist keys are namespaced by jti")
    void blacklistKeyLayout() {
        assertThat(TokenBlacklistKeys.forTokenId("abc-123"))
            .isEqualTo("jwt:blacklist:jti:abc-123");
    }

    @Test
    @DisplayName("audience values round-trip and expose their authority form")
    void audienceValues() {
        assertThat(TokenAudience.STOREFRONT.value()).isEqualTo("storefront");
        assertThat(TokenAudience.CONTROL.value()).isEqualTo("control");
        assertThat(TokenAudience.CONTROL.authority()).isEqualTo("AUD_control");
        assertThat(TokenAudience.fromValue("storefront")).isEqualTo(TokenAudience.STOREFRONT);

        assertThatThrownBy(() -> TokenAudience.fromValue("nope"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
