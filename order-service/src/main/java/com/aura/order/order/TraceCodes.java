package com.aura.order.order;

import java.security.SecureRandom;

/**
 * The code a customer quotes when they ring up about an order (requirement 11).
 *
 * <p>Ten characters of Crockford Base32 from {@link SecureRandom}, unrelated to the primary key.
 * A sequential id in a URL tells anyone who buys twice exactly how many orders the shop took in
 * between, and lets them read someone else's order by subtracting one.
 *
 * <p>Crockford's alphabet omits I, L, O and U — the first three because they are unreadable next
 * to 1 and 0 over the telephone, and U so that the generator cannot spell anything unfortunate.
 */
public final class TraceCodes {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceCodes() {
    }

    public static String generate() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            code[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }

    /**
     * Folds the characters Crockford treats as interchangeable onto the ones actually generated,
     * so a customer reading "O" for zero or "l" for one over the telephone still finds their order.
     */
    public static String normalise(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().toUpperCase(java.util.Locale.ROOT)
            .replace('O', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('U', 'V');
    }
}
