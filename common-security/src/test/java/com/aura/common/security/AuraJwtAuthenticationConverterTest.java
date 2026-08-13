package com.aura.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a validated token becomes authorities — the single point every {@code hasRole} and
 * {@code @ControlPanelOnly} check in every service depends on.
 *
 * <p>Worth testing precisely because it has been wrong before: the hand-rolled filter this replaced
 * parsed the roles claim and then handed Spring an empty authority list, so every role check
 * silently failed. Nothing threw, nothing logged, and the endpoints simply behaved as though
 * nobody had any permissions.
 */
class AuraJwtAuthenticationConverterTest {

    private final AuraJwtAuthenticationConverter converter = new AuraJwtAuthenticationConverter();

    private Jwt jwt(List<String> roles, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("42")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900));

        if (roles != null) {
            builder.claim(AuraClaims.ROLES, roles);
        }
        if (audience != null) {
            builder.audience(audience);
        }
        return builder.build();
    }

    private List<String> authoritiesOf(Jwt jwt) {
        AbstractAuthenticationToken token = converter.convert(jwt);
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    @DisplayName("roles become ROLE_-prefixed authorities, which is what hasRole matches")
    void rolesBecomeAuthorities() {
        assertThat(authoritiesOf(jwt(List.of("ADMIN", "SELLER"), List.of("control"))))
            .contains("ROLE_ADMIN", "ROLE_SELLER");
    }

    @Test
    @DisplayName("the audience becomes an authority, which is what gates the control panel")
    void audienceBecomesAnAuthority() {
        assertThat(authoritiesOf(jwt(List.of("ADMIN"), List.of("control"))))
            .contains("AUD_control");
    }

    @Test
    @DisplayName("a storefront token carries no control authority, however privileged its roles")
    void storefrontTokenGetsNoControlAuthority() {
        // The whole control-panel rule: an ADMIN who signed in through the storefront must not
        // reach control endpoints, and this is where that is decided.
        List<String> authorities = authoritiesOf(jwt(List.of("ADMIN"), List.of("storefront")));

        assertThat(authorities).contains("ROLE_ADMIN", "AUD_storefront");
        assertThat(authorities).doesNotContain("AUD_control");
    }

    @Test
    @DisplayName("an unrecognised audience grants nothing rather than failing")
    void unknownAudienceIsIgnored() {
        // A token minted for a surface this service does not serve is not an error - it simply
        // confers no audience authority.
        List<String> authorities = authoritiesOf(jwt(List.of("USER"), List.of("some-future-app")));

        assertThat(authorities).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("a token with no roles claim yields no role authorities, and does not blow up")
    void missingRolesClaimIsTolerated() {
        assertThat(authoritiesOf(jwt(null, List.of("storefront"))))
            .containsExactly("AUD_storefront");
    }

    @Test
    @DisplayName("an empty roles list is fine")
    void emptyRolesList() {
        assertThat(authoritiesOf(jwt(List.of(), List.of("storefront"))))
            .containsExactly("AUD_storefront");
    }

    @Test
    @DisplayName("the principal name is the user id, so getName() is directly usable")
    void principalNameIsTheUserId() {
        // CurrentUser parses this. The previous code used Long.getLong on it, which reads a system
        // property rather than parsing - so it always returned null and /me always NPE'd.
        assertThat(converter.convert(jwt(List.of("USER"), List.of("storefront"))).getName())
            .isEqualTo("42");
    }

    @Test
    @DisplayName("several audiences each contribute their own authority")
    void multipleAudiences() {
        assertThat(authoritiesOf(jwt(List.of("ADMIN"), List.of("control", "storefront"))))
            .contains("AUD_control", "AUD_storefront");
    }

    @Test
    @DisplayName("the authority list is never empty for a real token — the bug this replaced")
    void authoritiesAreNeverSilentlyDropped() {
        assertThat(converter.convert(jwt(List.of("ADMIN"), List.of("control"))).getAuthorities())
            .isNotEmpty();
    }
}
