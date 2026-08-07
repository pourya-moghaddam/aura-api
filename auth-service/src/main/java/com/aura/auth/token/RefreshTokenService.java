package com.aura.auth.token;

import com.aura.auth.config.TokenProperties;
import com.aura.auth.user.exception.InvalidCredentialsException;
import com.aura.common.security.TokenAudience;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Issues and rotates refresh tokens.
 *
 * <p>The token itself is 256 bits of {@link SecureRandom} output — opaque, not a JWT. Nothing needs
 * to read claims out of it; it exists purely to be exchanged at this service for a new access token,
 * so there is nothing a self-describing format would buy here, and an opaque token cannot be
 * inspected or tampered with client-side.
 *
 * <p>Stored as a plain SHA-256 hash rather than salted like a password. A password needs a salt
 * because users pick low-entropy values from a small effective space; this token is 256 bits we
 * generated ourselves, so brute-forcing the hash is not a realistic threat, and a per-token salt
 * would only cost us the ability to look a presented token up by hash directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenProperties tokenProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Starts a new family — used on login, never on rotation. */
    @Transactional
    public IssuedRefreshToken issueNew(Long userId, TokenAudience audience) {
        return issue(userId, audience, UUID.randomUUID());
    }

    /**
     * Exchanges a presented token for a new one in the same family.
     *
     * <p>The three failure cases are collapsed into the same generic exception on purpose — an
     * expired token, a garbage string, and an unknown token should not be distinguishable to the
     * caller, since that distinction is only useful to someone probing the endpoint.
     *
     * @throws RefreshTokenReuseException if the presented token was already consumed. The whole
     *                                    family is revoked as a side effect before this throws.
     */
    @Transactional
    public IssuedRefreshToken rotate(String presentedToken) {
        String hash = hash(presentedToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hash)
            .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired session."));

        if (existing.isConsumed() || existing.isRevoked()) {
            // Either genuine reuse, or a token from an already-killed family. Both mean: stop
            // trusting this family immediately, not just this one token.
            log.warn("Refresh token reuse detected for family {}", existing.getFamilyId());
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), Instant.now());
            throw new RefreshTokenReuseException();
        }

        if (existing.isExpired()) {
            throw new InvalidCredentialsException("Invalid or expired session.");
        }

        existing.setConsumedAt(Instant.now());
        refreshTokenRepository.save(existing);

        return issue(existing.getUserId(), existing.getAudience(), existing.getFamilyId());
    }

    /** Logout: kill every token descended from the presented one, not just the one presented. */
    @Transactional
    public void revokeFamilyContaining(String presentedToken) {
        refreshTokenRepository.findByTokenHash(hash(presentedToken))
            .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now()));
    }

    /** Password change: every existing session must re-authenticate, not just the one that changed it. */
    @Transactional
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    private IssuedRefreshToken issue(Long userId, TokenAudience audience, UUID familyId) {
        String rawToken = generateRawToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenProperties.refreshTokenTtl());

        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(rawToken));
        token.setFamilyId(familyId);
        token.setAudience(audience);
        token.setIssuedAt(now);
        token.setExpiresAt(expiresAt);
        refreshTokenRepository.save(token);

        return new IssuedRefreshToken(rawToken, expiresAt, userId, audience);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec; this cannot happen on any real JVM.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * @param userId   whose session this belongs to — the caller needs it to mint a matching
     *                 access token without an extra lookup of its own
     * @param audience which surface the corresponding access token must be minted for
     */
    public record IssuedRefreshToken(String rawToken, Instant expiresAt, Long userId, TokenAudience audience) {
    }
}
