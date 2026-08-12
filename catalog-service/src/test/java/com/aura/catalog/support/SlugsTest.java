package com.aura.catalog.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlugsTest {

    @ParameterizedTest
    @CsvSource({
        "Material,           material",
        "Sleeve Length,      sleeve-length",
        "  Screen  Size  ,   screen-size",
        "100% Cotton,        100-cotton",
        "Café,               cafe",
        "A---B,              a-b",
        "--Leading,          leading",
        "Trailing--,         trailing"
    })
    @DisplayName("derives a URL-safe slug from Latin text")
    void derivesFromLatinText(String input, String expected) {
        assertThat(Slugs.deriveOrNull(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"جنس", "سایز آستین", "٪١٠٠", "---", "   "})
    @DisplayName("returns null when nothing usable survives, rather than inventing an identifier")
    void returnsNullWhenNothingSurvives(String input) {
        assertThat(Slugs.deriveOrNull(input)).isNull();
    }

    @Test
    @DisplayName("returns null for null and empty input")
    void handlesAbsentInput() {
        assertThat(Slugs.deriveOrNull(null)).isNull();
        assertThat(Slugs.deriveOrNull("")).isNull();
    }

    @Test
    @DisplayName("mixed script keeps only what is URL-safe")
    void mixedScript() {
        assertThat(Slugs.deriveOrNull("Cotton پنبه")).isEqualTo("cotton");
    }
}
