package com.aura.order.discount;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.order.discount.dto.DiscountCodeRequest;
import com.aura.order.discount.dto.DiscountCodeResponse;
import com.aura.order.discount.dto.DiscountQuoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules a code is judged by, and the line between quoting one and spending one.
 *
 * <p>The pairing matters more than either half: every rejection is asserted for both paths,
 * because a rule enforced only at quote time is a discount a determined shopper can take by
 * posting the checkout directly, and one enforced only at redemption is a promise on the checkout
 * page that the order then breaks.
 */
@ExtendWith(MockitoExtension.class)
class DiscountServiceTest {

    @Mock
    private DiscountCodeRepository discountCodeRepository;

    @Mock
    private DiscountRedemptionRepository discountRedemptionRepository;

    @InjectMocks
    private DiscountService discountService;

    private DiscountCode discount;

    @BeforeEach
    void setUp() {
        discount = DiscountCode.of("SUMMER", DiscountType.PERCENTAGE, 10L);
        discount.setId(1L);
    }

    /** Both lookups answer with the same row, so a test can be written once for both paths. */
    private void stored(DiscountCode code) {
        when(discountCodeRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.of(code));
        when(discountCodeRepository.lockByCode(anyString())).thenReturn(Optional.of(code));
    }

    private void assertRefused(DiscountRejection reason, long subtotal, Long userId) {
        DiscountQuoteResponse quote = discountService.quote("SUMMER", subtotal, userId);
        assertThat(quote.valid()).isFalse();
        assertThat(quote.reasonCode()).isEqualTo(reason.code());
        assertThat(quote.discountAmount()).isZero();
        assertThat(quote.newTotal()).isEqualTo(subtotal);

        assertThatThrownBy(() -> discountService.redeem("SUMMER", subtotal, userId, 99L))
            .isInstanceOf(BusinessRuleException.class);
        verify(discountRedemptionRepository, never()).save(any());
    }

    @Nested
    @DisplayName("quoting")
    class Quoting {

        @Test
        @DisplayName("a valid code reports the amount off and the resulting total")
        void validCode() {
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));

            DiscountQuoteResponse quote = discountService.quote("SUMMER", 1_000_000L, 7L);

            assertThat(quote.valid()).isTrue();
            assertThat(quote.discountAmount()).isEqualTo(100_000L);
            assertThat(quote.newTotal()).isEqualTo(900_000L);
        }

        @Test
        @DisplayName("quoting spends nothing")
        void quotingIsFree() {
            // A shopper may retype the code a dozen times. If quoting incremented the counter, a
            // one-use code would be exhausted before anyone managed to buy anything with it.
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));

            discountService.quote("SUMMER", 1_000_000L, 7L);

            assertThat(discount.getTimesUsed()).isZero();
            verify(discountCodeRepository, never()).save(any());
            verify(discountRedemptionRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown code is a 200 saying no, not an error")
        void unknownCode() {
            when(discountCodeRepository.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());

            DiscountQuoteResponse quote = discountService.quote("nope", 500_000L, null);

            assertThat(quote.valid()).isFalse();
            assertThat(quote.reasonCode()).isEqualTo("discount-not-found");
            assertThat(quote.newTotal()).isEqualTo(500_000L);
        }

        @Test
        @DisplayName("the code is matched however the shopper typed it")
        void caseAndWhitespaceInsensitive() {
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));

            assertThat(discountService.quote("  summer ", 1_000_000L, null).valid()).isTrue();
        }

        @Test
        @DisplayName("the message for a minimum says what the minimum is")
        void minimumMessageIsActionable() {
            discount.setMinOrderTotal(2_000_000L);
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));

            assertThat(discountService.quote("SUMMER", 500_000L, null).message())
                .contains("2000000");
        }
    }

    @Nested
    @DisplayName("rejections, applied identically to quoting and redeeming")
    class Rejections {

        @Test
        @DisplayName("an inactive code is refused, and indistinguishable from one that does not exist")
        void inactive() {
            // Telling a stranger "that code exists but is switched off" invites guessing at the rest.
            discount.setActive(false);
            stored(discount);

            assertRefused(DiscountRejection.NOT_FOUND, 1_000_000L, null);
        }

        @Test
        @DisplayName("a code whose window has not opened is refused")
        void notStarted() {
            discount.setStartsAt(OffsetDateTime.now().plusDays(1));
            stored(discount);

            assertRefused(DiscountRejection.NOT_STARTED, 1_000_000L, null);
        }

        @Test
        @DisplayName("an expired code is refused")
        void expired() {
            discount.setEndsAt(OffsetDateTime.now().minusSeconds(1));
            stored(discount);

            assertRefused(DiscountRejection.EXPIRED, 1_000_000L, null);
        }

        @Test
        @DisplayName("a code at its usage limit is refused")
        void exhausted() {
            discount.setUsageLimit(3);
            discount.setTimesUsed(3);
            stored(discount);

            assertRefused(DiscountRejection.EXHAUSTED, 1_000_000L, null);
        }

        @Test
        @DisplayName("an order below the minimum is refused")
        void belowMinimum() {
            discount.setMinOrderTotal(1_000_000L);
            stored(discount);

            assertRefused(DiscountRejection.BELOW_MINIMUM, 999_999L, null);
        }

        @Test
        @DisplayName("a shopper at their per-user limit is refused")
        void perUserLimit() {
            discount.setPerUserLimit(1);
            stored(discount);
            when(discountRedemptionRepository.countByDiscountCodeIdAndUserId(1L, 7L)).thenReturn(1);

            assertRefused(DiscountRejection.PER_USER_LIMIT, 1_000_000L, 7L);
        }

        @Test
        @DisplayName("a code that would take off nothing is refused rather than wasted")
        void noEffect() {
            // Integer division: 1% of 50 Rial is zero. Spending a use of a limited code to take
            // off nothing is worse for the shopper than being told it does not apply.
            discount.setValue(1L);
            stored(discount);

            assertRefused(DiscountRejection.NO_EFFECT, 50L, null);
        }

        @Test
        @DisplayName("the minimum is inclusive: an order exactly at it qualifies")
        void minimumIsInclusive() {
            discount.setMinOrderTotal(1_000_000L);
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));

            assertThat(discountService.quote("SUMMER", 1_000_000L, null).valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("redeeming")
    class Redeeming {

        @Test
        @DisplayName("takes the lock rather than the plain read")
        void locksTheRow() {
            // The whole defence against two checkouts spending the last use is that this path
            // reads the row FOR UPDATE. Reading it unlocked would pass every other test here and
            // fail only under load.
            when(discountCodeRepository.lockByCode("SUMMER")).thenReturn(Optional.of(discount));

            discountService.redeem("SUMMER", 1_000_000L, null, 42L);

            verify(discountCodeRepository).lockByCode("SUMMER");
            verify(discountCodeRepository, never()).findByCodeIgnoreCase(anyString());
        }

        @Test
        @DisplayName("spends one use and records what was taken off")
        void recordsTheRedemption() {
            when(discountCodeRepository.lockByCode("SUMMER")).thenReturn(Optional.of(discount));

            DiscountService.Redemption redemption =
                discountService.redeem("SUMMER", 1_000_000L, 7L, 42L);

            assertThat(redemption.amount()).isEqualTo(100_000L);
            assertThat(redemption.discountCodeId()).isEqualTo(1L);
            // The code as stored, not as typed: it is what the order will display.
            assertThat(redemption.code()).isEqualTo("SUMMER");
            assertThat(discount.getTimesUsed()).isEqualTo(1);

            ArgumentCaptor<DiscountRedemption> saved =
                ArgumentCaptor.forClass(DiscountRedemption.class);
            verify(discountRedemptionRepository).save(saved.capture());
            assertThat(saved.getValue().getOrderId()).isEqualTo(42L);
            assertThat(saved.getValue().getUserId()).isEqualTo(7L);
            // Stored, not recomputed: the code's percentage can be edited tomorrow and the order
            // still has to say what this customer was actually given.
            assertThat(saved.getValue().getAmount()).isEqualTo(100_000L);
        }

        @Test
        @DisplayName("a guest redeems with no user attached")
        void guestRedemption() {
            // Requirement 12. The per-user limit cannot bind an anonymous shopper - there is no
            // "user" to count - so the overall usage limit is what bounds the code's exposure.
            discount.setPerUserLimit(1);
            when(discountCodeRepository.lockByCode("SUMMER")).thenReturn(Optional.of(discount));

            discountService.redeem("SUMMER", 1_000_000L, null, 42L);

            ArgumentCaptor<DiscountRedemption> saved =
                ArgumentCaptor.forClass(DiscountRedemption.class);
            verify(discountRedemptionRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isNull();
            verify(discountRedemptionRepository, never())
                .countByDiscountCodeIdAndUserId(anyLong(), any());
        }

        @Test
        @DisplayName("an unknown code refuses the checkout rather than passing it at full price")
        void unknownCodeThrows() {
            when(discountCodeRepository.lockByCode("NOPE")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> discountService.redeem("nope", 1_000_000L, null, 42L))
                .isInstanceOf(BusinessRuleException.class);
        }
    }

    @Nested
    @DisplayName("administration")
    class Administration {

        private DiscountCodeRequest request(String code, DiscountType type, long value) {
            return new DiscountCodeRequest(code, null, type, value, null, null, null, null,
                null, null, null);
        }

        @Test
        @DisplayName("a new code is stored upper-cased and trimmed")
        void normalisesOnCreate() {
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER")).thenReturn(Optional.empty());
            when(discountCodeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            DiscountCodeResponse created =
                discountService.create(request("  summer ", DiscountType.FIXED, 50_000L));

            assertThat(created.code()).isEqualTo("SUMMER");
        }

        @Test
        @DisplayName("a code that already exists in another case is a conflict")
        void duplicateCode() {
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));

            assertThatThrownBy(() ->
                discountService.create(request("summer", DiscountType.FIXED, 1L)))
                .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("a percentage over a hundred is refused with a readable message")
        void percentageOverAHundred() {
            assertThatThrownBy(() ->
                discountService.create(request("X", DiscountType.PERCENTAGE, 150L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cannot exceed 100");
        }

        @Test
        @DisplayName("a validity window that ends before it starts is refused")
        void backwardsWindow() {
            OffsetDateTime now = OffsetDateTime.now();
            DiscountCodeRequest backwards = new DiscountCodeRequest("X", null, DiscountType.FIXED,
                1L, null, null, null, null, now.plusDays(1), now, null);

            assertThatThrownBy(() -> discountService.create(backwards))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("after its start");
        }

        @Test
        @DisplayName("a ceiling on a fixed amount is dropped, because it means nothing")
        void capIgnoredForFixed() {
            when(discountCodeRepository.findByCodeIgnoreCase("X")).thenReturn(Optional.empty());
            when(discountCodeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            DiscountCodeResponse created = discountService.create(new DiscountCodeRequest("X", null,
                DiscountType.FIXED, 50_000L, 10_000L, null, null, null, null, null, null));

            assertThat(created.maxDiscount()).isNull();
        }

        @Test
        @DisplayName("editing a code leaves what it has already spent alone")
        void editKeepsHistory() {
            // times_used is history, not configuration. Resetting it on edit would silently hand
            // back every use of a limited code.
            discount.setTimesUsed(4);
            when(discountCodeRepository.findById(1L)).thenReturn(Optional.of(discount));
            when(discountCodeRepository.findByCodeIgnoreCase("SUMMER"))
                .thenReturn(Optional.of(discount));
            when(discountCodeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            DiscountCodeResponse updated =
                discountService.update(1L, request("SUMMER", DiscountType.FIXED, 25_000L));

            assertThat(updated.timesUsed()).isEqualTo(4);
            assertThat(updated.type()).isEqualTo(DiscountType.FIXED);
        }

        @Test
        @DisplayName("deactivating switches the code off without deleting it")
        void deactivate() {
            when(discountCodeRepository.findById(1L)).thenReturn(Optional.of(discount));
            when(discountCodeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            assertThat(discountService.deactivate(1L).isActive()).isFalse();
            verify(discountCodeRepository, never()).delete(any());
        }
    }
}
