package com.aura.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Token issuance settings. Auth-service is the only service that signs; everything else verifies
 * against the JWKS endpoint.
 *
 * @param issuer         value of the {@code iss} claim, and what every other service checks
 * @param accessTokenTtl deliberately short. The access token cannot be revoked directly, so its
 *                       lifetime is the window in which a stolen one stays useful. Revocation is
 *                       done by killing the refresh token instead.
 * @param cookieSecure   whether the refresh-token cookie carries the {@code Secure} attribute.
 *                       Must be true in production; defaults to false so local development over
 *                       plain HTTP does not silently fail refresh with no indication why browsers
 *                       are dropping the cookie.
 * @param rsa            signing keypair; see {@link RsaKeyProperties}
 */
@ConfigurationProperties("aura.auth.token")
public record TokenProperties(
    String issuer,
    Duration accessTokenTtl,
    Duration refreshTokenTtl,
    boolean cookieSecure,
    RsaKeyProperties rsa
) {

    public TokenProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(15);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(30);
        }
    }

    /**
     * @param privateKeyPem PKCS#8 PEM. Comes from a secret, never from a committed file.
     * @param publicKeyPem  X.509 PEM matching the private key
     * @param keyId         {@code kid} written into signed tokens and published in the JWK set.
     *                      Having one is what makes key rotation possible: during a rotation both
     *                      keys are published, and verifiers pick by {@code kid}.
     */
    public record RsaKeyProperties(String privateKeyPem, String publicKeyPem, String keyId) {

        public boolean isConfigured() {
            return privateKeyPem != null && !privateKeyPem.isBlank()
                && publicKeyPem != null && !publicKeyPem.isBlank();
        }
    }
}
