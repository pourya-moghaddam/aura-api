package com.aura.auth.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public half of the signing key so every other service can verify tokens without
 * holding a secret.
 *
 * <p>Mounted under {@code /api/auth} so the gateway's existing {@code /api/auth/**} route reaches
 * it without a special case.
 */
@RestController
@RequestMapping("/api/auth/.well-known")
@RequiredArgsConstructor
public class JwksController {

    private final RSAKey signingKey;

    @GetMapping("/jwks.json")
    public Map<String, Object> jwks() {
        // toPublicJWK() strips the private exponent. Without it this endpoint would publish the
        // signing key itself.
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}
