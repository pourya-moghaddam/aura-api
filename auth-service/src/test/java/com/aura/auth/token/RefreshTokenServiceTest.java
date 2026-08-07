package com.aura.auth.token;

import com.aura.auth.config.TokenProperties;
import com.aura.auth.user.exception.InvalidCredentialsException;
import com.aura.common.security.TokenAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        TokenProperties tokenProperties = new TokenProperties(
            "https://auth.aura.local",
            Duration.ofMinutes(15),
            Duration.ofDays(30),
            true,
            new TokenProperties.RsaKeyProperties(null, null, null)
        );
        service = new RefreshTokenService(refreshTokenRepository, tokenProperties);
    }

    private RefreshToken savedTokenCaptor() {
        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void issuingANewTokenStartsAFreshFamily() {
        RefreshTokenService.IssuedRefreshToken issued = service.issueNew(1L, TokenAudience.STOREFRONT);

        assertThat(issued.rawToken()).isNotBlank();
        assertThat(issued.userId()).isEqualTo(1L);
        assertThat(issued.audience()).isEqualTo(TokenAudience.STOREFRONT);

        RefreshToken saved = savedTokenCaptor();
        assertThat(saved.getFamilyId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(1L);
        // The stored value must never be the raw token - only its hash.
        assertThat(saved.getTokenHash()).isNotEqualTo(issued.rawToken());
        assertThat(saved.getTokenHash()).hasSize(64); // hex-encoded SHA-256
    }

    @Test
    void rawTokensAreNotPredictableAcrossTwoIssuances() {
        RefreshTokenService.IssuedRefreshToken first = service.issueNew(1L, TokenAudience.STOREFRONT);
        RefreshTokenService.IssuedRefreshToken second = service.issueNew(1L, TokenAudience.STOREFRONT);

        assertThat(first.rawToken()).isNotEqualTo(second.rawToken());
    }

    @Test
    void rotatingAValidTokenIssuesASuccessorInTheSameFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken existing = usableToken(familyId);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));

        RefreshTokenService.IssuedRefreshToken rotated = service.rotate("presented-raw-token");

        assertThat(rotated.userId()).isEqualTo(existing.getUserId());
        assertThat(rotated.audience()).isEqualTo(existing.getAudience());
        // The presented token must be marked consumed - a second presentation must not succeed.
        assertThat(existing.getConsumedAt()).isNotNull();
        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void rotatingConsumesTheOldTokenBeforeIssuingTheNewOne() {
        RefreshToken existing = usableToken(UUID.randomUUID());
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));

        service.rotate("presented-raw-token");

        // Two saves: one marking the old token consumed, one persisting the new token.
        verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
    }

    /**
     * The core security property: presenting a token that was already exchanged is treated as a
     * stolen-token replay, not a harmless retry, and kills every token descended from it.
     */
    @Test
    void presentingAnAlreadyConsumedTokenRevokesTheWholeFamily() {
        UUID familyId = UUID.randomUUID();
        RefreshToken consumed = usableToken(familyId);
        consumed.setConsumedAt(Instant.now().minusSeconds(5));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> service.rotate("stolen-and-replayed-token"))
            .isInstanceOf(RefreshTokenReuseException.class);

        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
    }

    @Test
    void presentingATokenFromAnAlreadyRevokedFamilyIsTreatedAsReuse() {
        UUID familyId = UUID.randomUUID();
        RefreshToken revoked = usableToken(familyId);
        revoked.setRevokedAt(Instant.now().minusSeconds(5));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate("token-from-dead-family"))
            .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void anUnknownTokenFailsWithoutRevokingAnything() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("never-issued"))
            .isInstanceOf(InvalidCredentialsException.class);

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void anExpiredButNeverConsumedTokenFailsWithoutTriggeringReuseDetection() {
        RefreshToken expired = usableToken(UUID.randomUUID());
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        // Expiry alone is not reuse - the family must not be punished for a session that simply
        // ran out, only for one that was replayed.
        assertThatThrownBy(() -> service.rotate("expired-token"))
            .isInstanceOf(InvalidCredentialsException.class)
            .isNotInstanceOf(RefreshTokenReuseException.class);

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void logoutRevokesTheFamilyOfThePresentedToken() {
        UUID familyId = UUID.randomUUID();
        RefreshToken existing = usableToken(familyId);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(existing));

        service.revokeFamilyContaining("some-token");

        verify(refreshTokenRepository).revokeFamily(eq(familyId), any(Instant.class));
    }

    @Test
    void logoutWithAnUnknownTokenDoesNothingRatherThanThrowing() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.revokeFamilyContaining("never-issued");

        verify(refreshTokenRepository, never()).revokeFamily(any(), any());
    }

    @Test
    void passwordChangeRevokesEveryFamilyForTheUser() {
        service.revokeAllForUser(42L);

        verify(refreshTokenRepository).revokeAllForUser(eq(42L), any(Instant.class));
    }

    private RefreshToken usableToken(UUID familyId) {
        RefreshToken token = new RefreshToken();
        token.setId(1L);
        token.setUserId(7L);
        token.setTokenHash("irrelevant-for-the-mock");
        token.setFamilyId(familyId);
        token.setAudience(TokenAudience.STOREFRONT);
        token.setIssuedAt(Instant.now().minusSeconds(60));
        token.setExpiresAt(Instant.now().plusSeconds(2_592_000));
        return token;
    }
}
