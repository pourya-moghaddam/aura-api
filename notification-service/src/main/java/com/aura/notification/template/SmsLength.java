package com.aura.notification.template;

/**
 * How many SMS segments a message costs.
 *
 * <p>The only thing that matters here: a single Persian character forces the whole message into
 * UCS-2, and the limits then drop from 160 characters to 70. A message of 71 characters costs
 * exactly twice one of 70, forever, on every order the shop ever ships. It is invisible in code
 * review and shows up as a line on a bill.
 */
public final class SmsLength {

    /** UCS-2, single segment. */
    public static final int SINGLE_SEGMENT = 70;

    /**
     * Concatenated segments carry a header, so each one holds three characters fewer. Not used to
     * enforce anything today — everything is kept to one segment — but the number is the reason
     * "just let it run to two" is not the cheap option it looks like.
     */
    public static final int CONCATENATED_SEGMENT = 67;

    private SmsLength() {
    }

    /**
     * Counts what the operator counts: UTF-16 code units.
     *
     * <p>Not code points and not {@code String.length()} by accident — for Persian the two agree,
     * but a zero-width non-joiner is a character the operator bills for even though it is invisible
     * in every editor. "آماده‌سازی" is ten characters, not nine.
     */
    public static int units(String message) {
        return message.length();
    }

    public static int segments(String message) {
        int units = units(message);
        if (units <= SINGLE_SEGMENT) {
            return units == 0 ? 0 : 1;
        }
        return (units + CONCATENATED_SEGMENT - 1) / CONCATENATED_SEGMENT;
    }

    public static boolean fitsOneSegment(String message) {
        return units(message) <= SINGLE_SEGMENT;
    }
}
