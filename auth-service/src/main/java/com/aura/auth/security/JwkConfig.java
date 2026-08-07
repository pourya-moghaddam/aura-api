package com.aura.auth.security;

import com.aura.auth.config.TokenProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Signing key, encoder, and local decoder for auth-service.
 *
 * <p>Asymmetric on purpose. With a shared HMAC secret every service would need the signing key in
 * its config, meaning any one of them could mint an admin token and a rotation would be a
 * coordinated redeploy of all of them. Here the private key exists in exactly one process.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class JwkConfig {

    private static final int DEV_KEY_SIZE = 2048;

    private final TokenProperties tokenProperties;
    private final Environment environment;

    @Bean
    public RSAKey signingKey() {
        TokenProperties.RsaKeyProperties rsa = tokenProperties.rsa();

        if (rsa != null && rsa.isConfigured()) {
            RSAPublicKey publicKey = RsaKeyLoader.publicKey(rsa.publicKeyPem());
            RSAPrivateKey privateKey = RsaKeyLoader.privateKey(rsa.privateKeyPem());
            return buildKey(publicKey, privateKey, rsa.keyId() != null ? rsa.keyId() : "aura-signing-key");
        }

        // A generated key is per-process: every restart invalidates every token in the wild, and
        // two instances would sign with different keys. Fine for a laptop, catastrophic in prod.
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException("""
                No RSA signing keypair configured. Set aura.auth.token.rsa.private-key-pem and \
                aura.auth.token.rsa.public-key-pem (via AURA_AUTH_TOKEN_RSA_* environment \
                variables). Refusing to start with an ephemeral key under the prod profile.""");
        }

        log.warn("No RSA signing keypair configured - generating an ephemeral one. "
            + "Tokens will not survive a restart and will not validate across instances.");
        KeyPair keyPair = generateKeyPair();
        return buildKey(
            (RSAPublicKey) keyPair.getPublic(),
            (RSAPrivateKey) keyPair.getPrivate(),
            "aura-dev-ephemeral"
        );
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey signingKey) {
        return new ImmutableJWKSet<>(new JWKSet(signingKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Auth-service validates its own tokens straight from the public key rather than fetching its
     * own JWKS over HTTP. Overrides the JWKS-based decoder that common-security would otherwise
     * auto-configure.
     */
    @Bean
    public JwtDecoder jwtDecoder(RSAKey signingKey) {
        RSAPublicKey publicKey;
        try {
            publicKey = signingKey.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Signing key does not expose a usable RSA public key", e);
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),
            new JwtIssuerValidator(tokenProperties.issuer())
        ));
        return decoder;
    }

    private RSAKey buildKey(RSAPublicKey publicKey, RSAPrivateKey privateKey, String keyId) {
        return new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID(keyId)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(DEV_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation unavailable", e);
        }
    }
}
