package com.aura.auth.security;

import com.aura.auth.config.TokenProperties;
import com.aura.auth.role.Role;
import com.aura.auth.user.User;
import com.aura.common.security.AuraClaims;
import com.aura.common.security.TokenAudience;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signs with a real keypair and verifies by decoding, rather than asserting on a mocked encoder's
 * arguments. The claim set is what every other service authorises against — {@code aud} is the
 * entire control-panel access rule, {@code roles} is every {@code hasRole} check — so the test that
 * matters is what a verifier actually reads back out, not what we intended to put in.
 */
class TokenIssuerTest {

    private static final String ISSUER = "https://auth.aura.local";
    private static final String KEY_ID = "test-key-1";

    private TokenIssuer tokenIssuer;
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        RSAKey signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .privateKey((RSAPrivateKey) keyPair.getPrivate())
            .keyID(KEY_ID)
            .build();

        TokenProperties properties = new TokenProperties(
            ISSUER, Duration.ofMinutes(15), Duration.ofDays(30), false, null);

        tokenIssuer = new TokenIssuer(
            new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey))),
            properties,
            signingKey);

        jwtDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
    }

    private User user(long id, String... roleNames) {
        User user = new User();
        user.setId(id);
        user.setRoles(java.util.Arrays.stream(roleNames)
            .map(name -> new Role(null, name, null))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
        return user;
    }

    @Test
    @DisplayName("a storefront token carries aud=storefront")
    void storefrontAudience() {
        String token = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT).value();

        assertThat(jwtDecoder.decode(token).getAudience()).containsExactly("storefront");
    }

    @Test
    @DisplayName("a control token carries aud=control, which is what gates the control panel")
    void controlAudience() {
        // An ADMIN who logged in through the storefront must not reach control routes; the
        // audience, not the role, is what the gateway rejects on.
        String token = tokenIssuer.issue(user(1L, "ADMIN"), TokenAudience.CONTROL).value();

        assertThat(jwtDecoder.decode(token).getAudience()).containsExactly("control");
    }

    @Test
    @DisplayName("roles reach the token unprefixed, so hasRole checks can match")
    void rolesAreCarried() {
        // Authorities were once discarded on the way back in, which made every hasRole() check
        // dead. Asserting on the decoded token is what would have caught that.
        String token = tokenIssuer.issue(user(1L, "ADMIN", "SELLER"), TokenAudience.CONTROL).value();

        assertThat(jwtDecoder.decode(token).getClaimAsStringList(AuraClaims.ROLES))
            .containsExactlyInAnyOrder("ADMIN", "SELLER");
    }

    @Test
    @DisplayName("a user with no roles gets an empty list, not a missing claim")
    void noRolesIsEmptyList() {
        String token = tokenIssuer.issue(user(1L), TokenAudience.STOREFRONT).value();

        assertThat(jwtDecoder.decode(token).getClaimAsStringList(AuraClaims.ROLES)).isEmpty();
    }

    @Test
    @DisplayName("the subject is the user id, which is what downstream services resolve identity from")
    void subjectIsUserId() {
        String token = tokenIssuer.issue(user(4242L, "USER"), TokenAudience.STOREFRONT).value();

        assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo("4242");
    }

    @Test
    @DisplayName("the issuer matches what every other service verifies against")
    void issuerIsSet() {
        String token = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT).value();

        assertThat(jwtDecoder.decode(token).getClaimAsString("iss")).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("the token expires within the configured TTL")
    void expiryFollowsTtl() {
        // The access token cannot be revoked, so its lifetime is the window a stolen one stays
        // useful. A TTL that silently failed to apply would not break anything visible.
        TokenIssuer.IssuedAccessToken issued = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT);
        Jwt decoded = jwtDecoder.decode(issued.value());

        assertThat(decoded.getExpiresAt()).isNotNull();
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
            .isEqualTo(Duration.ofMinutes(15));
        // Compared at second precision: `exp` is an epoch-seconds claim, so the token cannot carry
        // the sub-second part the in-memory Instant has. The two agree on what a verifier sees.
        assertThat(issued.expiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
            .isEqualTo(decoded.getExpiresAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("every token gets a unique jti, so the force-logout blacklist can name one")
    void jtiIsUniquePerToken() {
        TokenIssuer.IssuedAccessToken first = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT);
        TokenIssuer.IssuedAccessToken second = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT);

        assertThat(first.tokenId()).isNotEqualTo(second.tokenId());
        assertThat(UUID.fromString(first.tokenId())).isNotNull();
        assertThat(jwtDecoder.decode(first.value()).getId()).isEqualTo(first.tokenId());
    }

    @Test
    @DisplayName("the kid header names the signing key, so rotation with overlap is possible")
    void kidIsSet() {
        // During a rotation both keys are published; a verifier picks by kid. Without it, the
        // overlap window does not work and rotation becomes a coordinated redeploy.
        String token = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT).value();

        assertThat(jwtDecoder.decode(token).getHeaders()).containsEntry("kid", KEY_ID);
    }

    @Test
    @DisplayName("tokens are RS256, not HMAC")
    void algorithmIsRs256() {
        // The whole point of the asymmetric scheme is that verifying services hold no signing
        // capability. An HS256 token would mean every service could mint admin tokens.
        String token = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT).value();

        assertThat(jwtDecoder.decode(token).getHeaders()).containsEntry("alg", "RS256");
    }

    @Test
    @DisplayName("a token signed by a different key does not verify")
    void foreignKeyRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPublicKey otherPublic = (RSAPublicKey) generator.generateKeyPair().getPublic();
        JwtDecoder foreignDecoder = NimbusJwtDecoder.withPublicKey(otherPublic).build();

        String token = tokenIssuer.issue(user(1L, "USER"), TokenAudience.STOREFRONT).value();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> foreignDecoder.decode(token))
            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    @DisplayName("audience values and authorities line up with what security config expects")
    void audienceAuthorityMapping() {
        assertThat(TokenAudience.CONTROL.authority()).isEqualTo("AUD_control");
        assertThat(TokenAudience.STOREFRONT.authority()).isEqualTo("AUD_storefront");
        assertThat(TokenAudience.fromValue("control")).isEqualTo(TokenAudience.CONTROL);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TokenAudience.fromValue("nope"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("issuing does not mutate the user's roles")
    void doesNotMutateUser() {
        User user = user(1L, "ADMIN");
        Set<Role> before = Set.copyOf(user.getRoles());

        tokenIssuer.issue(user, TokenAudience.CONTROL);

        assertThat(user.getRoles()).containsExactlyInAnyOrderElementsOf(List.copyOf(before));
    }
}
