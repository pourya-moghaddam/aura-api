package com.aura.common.phone;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalises Iranian mobile numbers to canonical E.164 ({@code +989121234567}).
 *
 * <p>Every phone must pass through here <em>before</em> any database lookup. The same person will
 * type {@code 09121234567} on one visit and {@code +98 912 123 4567} on the next, and will paste
 * Persian digits from a contact list on a third. Comparing those raw creates several accounts for
 * one human, and the damage is invisible until someone cannot log in and their order history has
 * vanished — at which point the duplicates are already in production and expensive to merge.
 *
 * <p>Lives in a shared module because order-service captures a buyer phone at checkout. If the two
 * services normalised differently, a guest order would fail to link to the account that placed it.
 */
public final class IranianPhoneNumber {

    /** Iranian mobile numbers are +98 followed by 9 and nine more digits. */
    private static final Pattern CANONICAL = Pattern.compile("^\\+989\\d{9}$");

    /** Anything that is not a digit or a leading plus: spaces, dashes, parentheses, dots, RTL marks. */
    private static final Pattern NON_DIALLABLE = Pattern.compile("[^0-9+]");

    private static final char PERSIAN_ZERO = '۰';      // ۰
    private static final char ARABIC_INDIC_ZERO = '٠'; // ٠

    private IranianPhoneNumber() {
    }

    /**
     * @return canonical {@code +989XXXXXXXXX}
     * @throws InvalidPhoneNumberException if the input is not a valid Iranian mobile number
     */
    public static String normalize(String raw) {
        return tryNormalize(raw).orElseThrow(() -> new InvalidPhoneNumberException(
            "Not a valid Iranian mobile number."));
    }

    public static Optional<String> tryNormalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        String digits = NON_DIALLABLE.matcher(foldDigits(raw)).replaceAll("");
        if (digits.isEmpty()) {
            return Optional.empty();
        }

        // Reduce every way of writing the country code to a bare national number, then re-add it.
        String national = stripCountryCode(digits);
        if (national.startsWith("0")) {
            national = national.substring(1);
        }

        String canonical = "+98" + national;
        return CANONICAL.matcher(canonical).matches() ? Optional.of(canonical) : Optional.empty();
    }

    public static boolean isValid(String raw) {
        return tryNormalize(raw).isPresent();
    }

    /**
     * Accepts {@code +98…}, {@code 0098…}, {@code 98…}, or a bare national number.
     *
     * <p>The {@code 98} case is deliberately conditional: a national number may legitimately begin
     * with those digits once the leading zero is dropped, so stripping unconditionally would
     * mangle it. Only strip when what remains still looks like a full mobile number.
     */
    private static String stripCountryCode(String digits) {
        if (digits.startsWith("+98")) {
            return digits.substring(3);
        }
        if (digits.startsWith("0098")) {
            return digits.substring(4);
        }
        if (digits.startsWith("98") && digits.length() >= 12) {
            return digits.substring(2);
        }
        // A stray plus with some other country code is not ours to interpret.
        if (digits.startsWith("+")) {
            return digits.substring(1);
        }
        return digits;
    }

    /**
     * Converts Persian (U+06F0–U+06F9) and Arabic-Indic (U+0660–U+0669) digits to ASCII.
     *
     * <p>These arrive constantly — Persian keyboards produce them by default and they are what gets
     * copied out of contact apps. They are visually identical to a reader and completely different
     * to {@code equals}.
     */
    private static String foldDigits(String input) {
        StringBuilder folded = new StringBuilder(input.length());
        for (char c : input.toCharArray()) {
            if (c >= PERSIAN_ZERO && c <= PERSIAN_ZERO + 9) {
                folded.append((char) ('0' + (c - PERSIAN_ZERO)));
            } else if (c >= ARABIC_INDIC_ZERO && c <= ARABIC_INDIC_ZERO + 9) {
                folded.append((char) ('0' + (c - ARABIC_INDIC_ZERO)));
            } else {
                folded.append(c);
            }
        }
        return folded.toString();
    }
}
