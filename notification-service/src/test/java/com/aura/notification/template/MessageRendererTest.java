package com.aura.notification.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRendererTest {

    private static final String TRACE = "ABCDEFGHJK";

    private final MessageRenderer renderer = new MessageRenderer();

    private Map<String, String> values(String product) {
        Map<String, String> values = new HashMap<>();
        values.put("product", product);
        values.put("trace", TRACE);
        return values;
    }

    @ParameterizedTest
    @EnumSource(MessageTemplate.class)
    @DisplayName("every template fits one SMS segment with an ordinary product name")
    void everyTemplateFitsOneSegment(MessageTemplate template) {
        // "Men's cotton shirt, navy" - unremarkable for an Iranian catalogue.
        String message = renderer.render(template, values("پیراهن نخی مردانه سرمه‌ای"));

        // Persian forces UCS-2, so the limit is 70 rather than 160. Seventy-one characters costs
        // exactly twice seventy, on every notification the shop ever sends.
        assertThat(SmsLength.segments(message)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(MessageTemplate.class)
    @DisplayName("a very long product name is trimmed rather than allowed to spill")
    void longNamesAreTrimmed(MessageTemplate template) {
        String message = renderer.render(template,
            values("کوله‌پشتی کوهنوردی ضدآب چهل لیتری با روکش باران و بند شکمی تقویت‌شده"));

        assertThat(SmsLength.segments(message)).isEqualTo(1);
    }

    @Test
    @DisplayName("the trace code survives trimming intact")
    void theTraceCodeIsNeverTrimmed() {
        // The one thing the shopper has to type back into the order lookup. Half a trace code
        // looks like information and is not.
        String message = renderer.render(MessageTemplate.ORDER_SHIPPED,
            values("کوله‌پشتی کوهنوردی ضدآب چهل لیتری با روکش باران و بند شکمی تقویت‌شده و کمربند"));

        assertThat(message).contains(TRACE);
        assertThat(SmsLength.segments(message)).isEqualTo(1);
    }

    @Test
    @DisplayName("a trimmed name says so")
    void trimmingIsVisible() {
        String message = renderer.render(MessageTemplate.ORDER_SHIPPED,
            values("کوله‌پشتی کوهنوردی ضدآب چهل لیتری با روکش باران و بند شکمی تقویت‌شده"));

        // Without the ellipsis a cut name reads as a typo in the catalogue rather than as a
        // shortened message.
        assertThat(message).contains("…");
    }

    @Test
    @DisplayName("a short name is left exactly as the seller wrote it")
    void shortNamesAreUntouched() {
        String message = renderer.render(MessageTemplate.ORDER_SHIPPED, values("کفش"));

        assertThat(message).isEqualTo("ارسال شد:\nکفش\nکد پیگیری: " + TRACE);
    }

    @Test
    @DisplayName("a missing value is refused rather than sent with a hole in it")
    void missingValuesThrow() {
        Map<String, String> incomplete = new HashMap<>();
        incomplete.put("trace", TRACE);

        // "{product} ارسال شد" cannot be recalled once it reaches a phone. Failing here sends the
        // message to the dead-letter queue instead, where someone sees it.
        assertThatThrownBy(() -> renderer.render(MessageTemplate.ORDER_SHIPPED, incomplete))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("product");
    }

    @Test
    @DisplayName("a blank value counts as missing")
    void blankValuesThrow() {
        assertThatThrownBy(() -> renderer.render(MessageTemplate.ORDER_SHIPPED, values("   ")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the delivered message does not name a product at all")
    void deliveredIsAboutTheWholeOrder() {
        Map<String, String> traceOnly = new HashMap<>();
        traceOnly.put("trace", TRACE);

        // Per order, not per item: hearing "delivered" three times for one parcel reads like a
        // mistake, and by then the shopper is holding the goods.
        String message = renderer.render(MessageTemplate.ORDER_DELIVERED, traceOnly);

        assertThat(message).isEqualTo("سفارش شما تحویل داده شد.\nکد پیگیری: " + TRACE);
    }

    @Test
    @DisplayName("no template renders a placeholder that nothing supplies")
    void noTemplateHasAnUnknownPlaceholder() {
        for (MessageTemplate template : MessageTemplate.values()) {
            assertThat(template.placeholders())
                .allMatch(name -> name.equals("product") || name.equals("trace"),
                    "templates may only use {product} and {trace}");
        }
    }

    @Test
    @DisplayName("a zero-width non-joiner is counted, because the operator counts it")
    void zwnjCosts() {
        // "آماده‌سازی" is ten characters, not nine. Invisible in every editor, billed for all the
        // same, and Persian compound words are full of them.
        assertThat(SmsLength.units("آماده‌سازی")).isEqualTo(10);
        assertThat(SmsLength.units("آمادهسازی")).isEqualTo(9);
    }

    @Test
    @DisplayName("segment counting matches what an operator charges")
    void segmentBoundaries() {
        assertThat(SmsLength.segments("ا".repeat(70))).isEqualTo(1);
        // One character over and the message is split, each part carrying a header - so 71
        // characters costs two segments, not one and a bit.
        assertThat(SmsLength.segments("ا".repeat(71))).isEqualTo(2);
        assertThat(SmsLength.segments("ا".repeat(134))).isEqualTo(2);
        assertThat(SmsLength.segments("ا".repeat(135))).isEqualTo(3);
        assertThat(SmsLength.segments("")).isZero();
    }
}
