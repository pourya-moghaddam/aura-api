package com.aura.common.phone;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IranianPhoneNumberTest {

    private static final String CANONICAL = "+989121234567";

    /**
     * Every one of these is the same human. Treating any two of them as different accounts is the
     * failure this class exists to prevent.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "+989121234567",
        "989121234567",
        "00989121234567",
        "09121234567",
        "9121234567",
        "0912 123 4567",
        "0912-123-4567",
        "(0912) 123-4567",
        "+98 912 123 4567",
        "  09121234567  ",
        "۰۹۱۲۱۲۳۴۵۶۷",              // Persian digits - what a Persian keyboard produces by default
        "٠٩١٢١٢٣٤٥٦٧",              // Arabic-Indic digits
        "+۹۸۹۱۲۱۲۳۴۵۶۷",            // Persian digits with a Latin plus
        "۰۹۱۲ ۱۲۳ ۴۵۶۷",            // Persian digits with separators
        "0912۱۲۳4567"               // mixed scripts, as happens when pasting into a typed field
    })
    void normalisesEveryWayOfWritingTheSameNumber(String input) {
        assertThat(IranianPhoneNumber.normalize(input)).isEqualTo(CANONICAL);
    }

    @Test
    void isIdempotent() {
        String once = IranianPhoneNumber.normalize("09121234567");
        assertThat(IranianPhoneNumber.normalize(once)).isEqualTo(once);
    }

    /**
     * A national number beginning with 98 must not have those digits eaten as a country code.
     * This is why the country-code strip is length-guarded rather than unconditional.
     */
    @Test
    void doesNotMistakeALeading98InTheNationalNumberForACountryCode() {
        assertThat(IranianPhoneNumber.normalize("09812345678")).isEqualTo("+989812345678");
        assertThat(IranianPhoneNumber.normalize("9812345678")).isEqualTo("+989812345678");
        assertThat(IranianPhoneNumber.normalize("989812345678")).isEqualTo("+989812345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "0212345678",      // Tehran landline - not a mobile
        "02112345678",     // landline with area code
        "091212345",       // too short
        "0912123456789",   // too long
        "8121234567",      // does not start with 9 after the country code
        "not a number",
        "+1 555 123 4567", // valid E.164, wrong country
        "+++",
        "0"
    })
    void rejectsThingsThatAreNotIranianMobileNumbers(String input) {
        assertThat(IranianPhoneNumber.isValid(input)).isFalse();
        assertThatThrownBy(() -> IranianPhoneNumber.normalize(input))
            .isInstanceOf(InvalidPhoneNumberException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsAbsentInput(String input) {
        assertThat(IranianPhoneNumber.tryNormalize(input)).isEmpty();
    }

    @Test
    void tryNormalizeReturnsEmptyRatherThanThrowing() {
        assertThat(IranianPhoneNumber.tryNormalize("garbage")).isEmpty();
        assertThat(IranianPhoneNumber.tryNormalize("09121234567")).contains(CANONICAL);
    }
}
