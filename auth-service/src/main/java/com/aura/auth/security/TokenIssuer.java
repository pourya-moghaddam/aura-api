package com.aura.auth.security;

import com.aura.auth.config.TokenProperties;
import com.aura.auth.role.Role;
import com.aura.auth.user.User;
import com.aura.common.security.AuraClaims;
import com.aura.common.security.TokenAudience;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Mints signed access tokens. Replaces the previous hand-rolled {@code JwtService}.
 */
@Service
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final TokenProperties tokenProperties;
    private final RSAKey signingKey;

    /**
     * @param audience which surface the token is for. Callers must only pass
     *                 {@link TokenAudience#CONTROL} after confirming the user holds a control role
     *                 — this method does not check, it only stamps.
     */
    public IssuedAccessToken issue(User user, TokenAudience audience) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(tokenProperties.accessTokenTtl());
        String tokenId = UUID.randomUUID().toString();

        List<String> roles = user.getRoles().stream()
            .map(Role::getName)
            .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(tokenProperties.issuer())
            .subject(user.getId().toString())
            .audience(List.of(audience.value()))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .id(tokenId)
            .claim(AuraClaims.ROLES, roles)
            .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
            .keyId(signingKey.getKeyID())
            .build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, tokenId, expiresAt);
    }

    public record IssuedAccessToken(String value, String tokenId, Instant expiresAt) {
    }
}
