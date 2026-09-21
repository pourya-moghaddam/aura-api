package com.aura.notification.template;

import java.util.List;

/**
 * The Persian copy, in one place.
 *
 * <p>Held here rather than beside the code that sends it, because copy changes for reasons that
 * have nothing to do with delivery — someone reads a message on their own phone and decides it
 * sounds wrong — and those two things should not need the same review.
 *
 * <p>One-time codes are absent on purpose. Iranian operators require them to go through a template
 * registered with the provider in advance; free-text OTP is refused outright, so there is no copy
 * on this side to write.
 *
 * <p>The copy is deliberately in label form — a status, then the product on its own line — rather
 * than a sentence with the product embedded in it. Product names are the one variable-length part
 * and sometimes have to be shortened; a trimmed label still reads as a label, while a trimmed
 * sentence reads as a mistake in the catalogue. It also buys back the characters a sentence spends
 * on grammar, which is what keeps these inside one segment.
 *
 * <p><strong>Every message here fits one SMS segment.</strong> Persian forces UCS-2 encoding, which
 * gives 70 characters, and crossing that line silently doubles the cost of every notification the
 * shop sends. {@link MessageRenderer} enforces it by shortening the product name — the only part
 * that can vary — rather than by letting a long name spill into a second segment.
 */
public enum MessageTemplate {

    /**
     * A seller has started work on one of the lines.
     *
     * <p>Worth sending because it is the first sign a real person saw the order. Between paying and
     * this, the shopper has no evidence anything happened.
     */
    ORDER_PROCESSING("در حال آماده‌سازی:\n{product}\nکد پیگیری: {trace}"),

    ORDER_SHIPPED("ارسال شد:\n{product}\nکد پیگیری: {trace}"),

    /**
     * The whole order arrived. Not per item: hearing "delivered" three times for one parcel reads
     * like a mistake, and by this point the shopper is holding the goods anyway.
     */
    ORDER_DELIVERED("سفارش شما تحویل داده شد.\nکد پیگیری: {trace}"),

    ORDER_CANCELLED("لغو شد:\n{product}\nکد پیگیری: {trace}");

    /**
     * The placeholder allowed to be shortened when the message would not otherwise fit.
     *
     * <p>It is the product name and never the trace code: the code is the one thing the shopper has
     * to type back, and half of it is worse than useless.
     */
    static final String ELASTIC = "product";

    static final String TRACE = "trace";

    private final String pattern;

    MessageTemplate(String pattern) {
        this.pattern = pattern;
    }

    public String pattern() {
        return pattern;
    }

    /** Which placeholders this template needs supplied. */
    public List<String> placeholders() {
        return Placeholders.in(pattern);
    }
}
