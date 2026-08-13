package com.aura.order.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The code a customer reads down the telephone.
 *
 * <p>Two properties matter and neither is about randomness for its own sake: it must not leak how
 * many orders the shop has taken, and it must survive being dictated aloud.
 */
class TraceCodesTest {

    @Test
    @DisplayName("ten characters, from Crockford's alphabet only")
    void shape() {
        for (int i = 0; i < 200; i++) {
            assertThat(TraceCodes.generate())
                .hasSize(10)
                .matches("[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{10}");
        }
    }

    @Test
    @DisplayName("the ambiguous letters are never generated")
    void omitsAmbiguousLetters() {
        // I and 1, O and 0, L and 1 are indistinguishable over the telephone. U is omitted so the
        // generator cannot spell anything the shop would rather it did not.
        for (int i = 0; i < 500; i++) {
            assertThat(TraceCodes.generate()).doesNotContain("I", "L", "O", "U");
        }
    }

    @Test
    @DisplayName("codes do not repeat in any practical run")
    void distinct() {
        // Not a uniqueness guarantee - that is the database's job - but a broken random source
        // would show up here immediately rather than as a constraint violation in production.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) {
            seen.add(TraceCodes.generate());
        }
        assertThat(seen).hasSize(5_000);
    }

    @Test
    @DisplayName("a customer reading O for zero still finds their order")
    void normalisesAmbiguousInput() {
        assertThat(TraceCodes.normalise("O1L2I3")).isEqualTo("011213");
        assertThat(TraceCodes.normalise("uvw")).isEqualTo("VVW");
    }

    @Test
    @DisplayName("case and stray whitespace are forgiven")
    void normalisesCaseAndSpace() {
        assertThat(TraceCodes.normalise("  ab3x9  ")).isEqualTo("AB3X9");
    }

    @Test
    @DisplayName("a generated code survives normalisation unchanged")
    void generatedCodesAreStable() {
        // The round trip has to be a no-op, or half the codes issued would fail their own lookup.
        for (int i = 0; i < 500; i++) {
            String code = TraceCodes.generate();
            assertThat(TraceCodes.normalise(code)).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("no code is empty or null-derived")
    void nullIsEmpty() {
        assertThat(TraceCodes.normalise(null)).isEmpty();
    }
}
