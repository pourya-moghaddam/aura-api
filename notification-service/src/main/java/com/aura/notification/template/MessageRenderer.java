package com.aura.notification.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Turns a template and some values into the text a shopper reads.
 *
 * <p>Two rules, both of which exist because the alternative is a real message on a real phone:
 * a missing value is a failure rather than a literal {@code {product}} sent to a customer, and the
 * result always fits one SMS segment.
 */
@Slf4j
@Component
public class MessageRenderer {

    /** What a shortened product name ends with, so it reads as trimmed rather than as a typo. */
    private static final String ELLIPSIS = "…";

    public String render(MessageTemplate template, Map<String, String> values) {
        List<String> required = template.placeholders();

        for (String name : required) {
            String value = values.get(name);
            if (value == null || value.isBlank()) {
                // Loudly. A template rendered with a hole in it goes out as "{product} ارسال شد"
                // and cannot be recalled; failing here sends it to the DLQ instead, where someone
                // sees it.
                throw new IllegalArgumentException(
                    "Template " + template + " needs a value for {" + name + "}");
            }
        }

        String message = fill(template, values, values.get(MessageTemplate.ELASTIC));
        if (SmsLength.fitsOneSegment(message)) {
            return message;
        }

        String shortened = shorten(template, values, message);
        if (!SmsLength.fitsOneSegment(shortened)) {
            // The fixed part alone is too long, which is a copy problem rather than a data problem.
            // Templates are pinned to one segment by test, so this means someone edited one past
            // the limit; say so rather than quietly billing twice.
            log.warn("Template {} does not fit one segment even with nothing elastic left ({} units)",
                template, SmsLength.units(shortened));
        }
        return shortened;
    }

    /**
     * Trims the product name until the whole message fits.
     *
     * <p>The product name and nothing else. The trace code is what the shopper types into the order
     * lookup, and a truncated one is worse than no message at all — it looks like information and
     * is not.
     */
    private String shorten(MessageTemplate template, Map<String, String> values, String rendered) {
        String elastic = values.get(MessageTemplate.ELASTIC);
        if (elastic == null) {
            return rendered;
        }

        int overflow = SmsLength.units(rendered) - SmsLength.SINGLE_SEGMENT;
        // One more for the ellipsis, which replaces a character rather than being free.
        int keep = elastic.length() - overflow - ELLIPSIS.length();
        if (keep < 1) {
            return fill(template, values, "");
        }

        return fill(template, values, elastic.substring(0, keep).stripTrailing() + ELLIPSIS);
    }

    private String fill(MessageTemplate template, Map<String, String> values, String elastic) {
        String message = template.pattern();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = MessageTemplate.ELASTIC.equals(entry.getKey())
                ? elastic : entry.getValue();
            message = Placeholders.replace(message, entry.getKey(), value);
        }
        return message.strip();
    }
}
