package com.aura.auth.token;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Kills every token in a family in one statement, rather than loading and saving each row.
     * Used both for the normal case (logout) and the abnormal one (reuse detected).
     *
     * <p>{@code REQUIRES_NEW} is load-bearing, not defensive. {@link RefreshTokenService#rotate}
     * calls this and then immediately throws {@link RefreshTokenReuseException} in the same method.
     * With the default propagation, that throw rolls back the surrounding {@code @Transactional},
     * undoing this very revocation — the one statement whose entire purpose is to survive the
     * failure. Forcing a new, independent transaction here means the revocation commits regardless
     * of what the caller does next. Confirmed against a live database: without this, a token from
     * the rotation that triggered reuse detection was still accepted afterward.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    void revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Every family for a user, in one statement. Used when a password changes — every existing
     * session should require re-authentication, not just the one that changed it.
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    void revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Periodic cleanup so the table does not grow forever with rows nobody will query again. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
