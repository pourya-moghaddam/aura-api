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

    @ParameterizedTest
    @ValueSource(strings = {
        "پیراهن مردانه 123",   // Latin digits in a Persian name
        "کتاب‌ها ۱۲۳",          // Persian digits, which fold to Latin ones
        "123",
        "2024",
        "۱۲۳ ۴۵۶"
    })
    @DisplayName("digits alone are not a slug")
    void digitsAreNotASlug(String input) {
        // The case that actually bit: stripping the Persian letters leaves the number behind, so
        // this would otherwise derive "123" - meaningless on its own, and the same slug for every
        // Persian product whose name ends in those digits. A shop would see one product refuse
        // another for reasons an admin cannot see from the names.
        assertThat(Slugs.deriveOrNull(input)).isNull();
    }

    @Test
    @DisplayName("two Persian names ending in the same digits do not collide, they both ask")
    void persianNamesDoNotCollide() {
        assertThat(Slugs.deriveOrNull("پیراهن مردانه 123")).isNull();
        assertThat(Slugs.deriveOrNull("کتاب‌ها 123")).isNull();
    }

    @Test
    @DisplayName("a Latin name with digits is unaffected")
    void latinWithDigitsStillWorks() {
        // The change must not cost the ordinary case anything.
        assertThat(Slugs.deriveOrNull("Nike Air Max 90")).isEqualTo("nike-air-max-90");
        assertThat(Slugs.deriveOrNull("iPhone 15 Pro")).isEqualTo("iphone-15-pro");
        assertThat(Slugs.deriveOrNull("Size 42")).isEqualTo("size-42");
    }
}
