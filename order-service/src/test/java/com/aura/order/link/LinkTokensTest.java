package com.aura.order.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The token in a seller's order link.
 *
 * <p>It is the <em>only</em> credential protecting the order behind it — the buyer has no account,
 * and whoever holds the link can pay. So the properties tested here are the ones that would make
 * it forgeable or recoverable.
 */
class LinkTokensTest {

    @Test
    @DisplayName("a token is 256 bits, URL-safe")
    void shape() {
        String token = LinkTokens.generate();

        // 32 bytes as unpadded base64url. Anything shorter is guessable at scale; anything not
        // URL-safe breaks the moment it is put in a link, which is its only purpose.
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("tokens do not repeat")
    void distinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            seen.add(LinkTokens.generate());
        }
        assertThat(seen).hasSize(5_000);
    }

    @Test
    @DisplayName("the stored hash does not contain the token")
    void hashIsNotReversible() {
        // A leaked database must not hand out working links. Same reasoning as the refresh tokens
        // in auth-service, for the same reason.
        String token = LinkTokens.generate();

        assertThat(LinkTokens.hash(token)).doesNotContain(token);
    }

    @Test
    @DisplayName("the hash is 64 hex characters, which is what the column holds")
    void hashShape() {
        // The column is VARCHAR(64). A longer digest would be silently truncated by some drivers
        // and rejected by others; either way lookups would start failing.
        assertThat(LinkTokens.hash(LinkTokens.generate())).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the same token always hashes the same way")
    void hashIsStable() {
        // Lookup is by hash. An unstable digest would mean every link stopped working.
        String token = LinkTokens.generate();

        assertThat(LinkTokens.hash(token)).isEqualTo(LinkTokens.hash(token));
    }

    @Test
    @DisplayName("different tokens hash differently")
    void hashesAreDistinct() {
        assertThat(LinkTokens.hash(LinkTokens.generate()))
            .isNotEqualTo(LinkTokens.hash(LinkTokens.generate()));
    }
}
