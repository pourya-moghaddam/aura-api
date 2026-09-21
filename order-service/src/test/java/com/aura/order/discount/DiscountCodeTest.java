package com.aura.order.discount;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arithmetic, on its own.
 *
 * <p>Money here is Rial as a whole number, so every case below is exact — there is no rounding
 * mode to argue about, only integer division and two ceilings. These are the sums a customer sees
 * on their invoice, which is reason enough to pin each one rather than trust the expression.
 */
class DiscountCodeTest {

    private DiscountCode percentage(long percent, Long cap) {
        DiscountCode discount = DiscountCode.of("SAVE", DiscountType.PERCENTAGE, percent);
        discount.setMaxDiscount(cap);
        return discount;
    }

    @Test
    @DisplayName("a percentage takes its share of the subtotal")
    void percentageOfSubtotal() {
        assertThat(percentage(10, null).discountFor(1_000_000L)).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("a percentage is capped by the maximum discount when one is set")
    void percentageRespectsTheCap() {
        // Without this, "20% off" against a ten-million-Rial order quietly gives away two million.
        assertThat(percentage(20, 300_000L).discountFor(10_000_000L)).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("the cap does not raise a discount that falls short of it")
    void capIsACeilingNotAFloor() {
        assertThat(percentage(20, 300_000L).discountFor(500_000L)).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("integer division rounds in the shop's favour, by at most a Rial")
    void integerDivision() {
        // 3% of 1001 is 30.03. Anything fractional is unpayable - no gateway accepts a third of a
        // Rial - so the remainder is dropped rather than rounded up against the customer.
        assertThat(percentage(3, null).discountFor(1001L)).isEqualTo(30L);
    }

    @Test
    @DisplayName("a fixed amount is taken off flat")
    void fixedAmount() {
        assertThat(DiscountCode.of("OFF", DiscountType.FIXED, 50_000L).discountFor(200_000L))
            .isEqualTo(50_000L);
    }

    @Test
    @DisplayName("a fixed amount larger than the order takes the order to zero, not below")
    void fixedAmountCannotExceedTheOrder() {
        // 500,000 off a 300,000 basket is a free basket, not a 200,000 refund. Letting this go
        // negative would mean charging the customer a negative total and paying them to shop.
        assertThat(DiscountCode.of("BIG", DiscountType.FIXED, 500_000L).discountFor(300_000L))
            .isEqualTo(300_000L);
    }

    @Test
    @DisplayName("a hundred percent takes the whole order and no more")
    void fullPercentage() {
        assertThat(percentage(100, null).discountFor(750_000L)).isEqualTo(750_000L);
    }

    @Test
    @DisplayName("nothing off an empty basket")
    void emptyBasket() {
        assertThat(percentage(50, null).discountFor(0L)).isZero();
        assertThat(DiscountCode.of("OFF", DiscountType.FIXED, 50_000L).discountFor(0L)).isZero();
    }

    @Test
    @DisplayName("a window with no bounds is always open")
    void unboundedWindow() {
        assertThat(DiscountCode.of("X", DiscountType.FIXED, 1L).isLiveAt(OffsetDateTime.now()))
            .isTrue();
    }

    @Test
    @DisplayName("the window is inclusive of its start and exclusive of its end")
    void windowBoundaries() {
        OffsetDateTime now = OffsetDateTime.now();
        DiscountCode discount = DiscountCode.of("X", DiscountType.FIXED, 1L);
        discount.setStartsAt(now);
        discount.setEndsAt(now.plusHours(1));

        assertThat(discount.isLiveAt(now)).isTrue();
        assertThat(discount.isLiveAt(now.minusSeconds(1))).isFalse();
        assertThat(discount.isLiveAt(now.plusHours(1))).isFalse();
    }

    @Test
    @DisplayName("an inactive code is never live, whatever its window says")
    void inactiveIsNeverLive() {
        DiscountCode discount = DiscountCode.of("X", DiscountType.FIXED, 1L);
        discount.setActive(false);

        assertThat(discount.isLiveAt(OffsetDateTime.now())).isFalse();
    }

    @Test
    @DisplayName("no usage limit means uses never run out")
    void unlimitedUses() {
        DiscountCode discount = DiscountCode.of("X", DiscountType.FIXED, 1L);
        discount.setTimesUsed(1_000_000);

        assertThat(discount.hasUsesLeft()).isTrue();
    }

    @Test
    @DisplayName("the last use is available, the one after it is not")
    void usesRunOut() {
        DiscountCode discount = DiscountCode.of("X", DiscountType.FIXED, 1L);
        discount.setUsageLimit(2);

        discount.setTimesUsed(1);
        assertThat(discount.hasUsesLeft()).isTrue();
        discount.setTimesUsed(2);
        assertThat(discount.hasUsesLeft()).isFalse();
    }

    @Test
    @DisplayName("a limit already exceeded stays exhausted rather than wrapping around")
    void loweredLimitExhausts() {
        // An admin lowering a limit below what has been spent is saying "no more", not "start again".
        DiscountCode discount = DiscountCode.of("X", DiscountType.FIXED, 1L);
        discount.setUsageLimit(2);
        discount.setTimesUsed(6);

        assertThat(discount.hasUsesLeft()).isFalse();
    }
}
