package com.aura.order.discount;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.discount.dto.DiscountCodeRequest;
import com.aura.order.discount.dto.DiscountCodeResponse;
import com.aura.order.discount.dto.DiscountQuoteResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Discount codes: what an admin defines, what checkout quotes, and what an order spends.
 *
 * <p>The distinction that matters here is between <em>quoting</em> and <em>redeeming</em>.
 * {@link #quote} is a read with no lock — it answers "what would this do", may be called on every
 * keystroke, and is never a promise. {@link #redeem} runs inside the order's transaction, takes a
 * row lock on the code first, and re-checks everything the quote checked, because the quote may
 * have been minutes ago and the last use of a limited code may have gone in between.
 *
 * <p>All money is Rial as a whole number. A percentage is integer division, which rounds in the
 * shop's favour by a Rial at most — the alternative is fractional currency that no gateway accepts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountService {

    private final DiscountCodeRepository discountCodeRepository;
    private final DiscountRedemptionRepository discountRedemptionRepository;

    // --- checkout ------------------------------------------------------------------------------

    /**
     * What a code would take off this subtotal, without committing to it.
     *
     * <p>{@code userId} may be null: a guest reaches checkout without an account (requirement 12),
     * and their per-user limit cannot be enforced because there is no "user" to count. That is a
     * deliberate gap, not an oversight — the overall {@code usageLimit} is what bounds a code's
     * exposure to anonymous shoppers.
     */
    @Transactional(readOnly = true)
    public DiscountQuoteResponse quote(String code, long subtotal, Long userId) {
        String normalised = normalise(code);

        Optional<DiscountCode> found = discountCodeRepository.findByCodeIgnoreCase(normalised);
        if (found.isEmpty()) {
            return DiscountQuoteResponse.rejected(normalised, subtotal,
                DiscountRejection.NOT_FOUND.code(), DiscountRejection.NOT_FOUND.message());
        }

        DiscountCode discount = found.get();
        Optional<DiscountRejection> rejection = validate(discount, subtotal, userId);

        return rejection
            .map(reason -> DiscountQuoteResponse.rejected(discount.getCode(), subtotal,
                reason.code(), reason.messageFor(discount)))
            .orElseGet(() -> DiscountQuoteResponse.accepted(discount.getCode(), subtotal,
                discount.discountFor(subtotal)));
    }

    /**
     * Spends one use of a code against an order, and returns the amount taken off.
     *
     * <p>Called from within the order-creation transaction, never on its own — {@code MANDATORY}
     * enforces that. If it ran in a transaction of its own, the use would be counted and the
     * redemption row written even when the order that justified them went on to fail, and the code
     * would leak uses to every abandoned checkout.
     *
     * <p>The lock is taken before anything is read, so two checkouts racing for the last use are
     * serialised: the second blocks, then sees the incremented counter and is refused.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long redeem(String code, long subtotal, Long userId, long orderId) {
        DiscountCode discount = discountCodeRepository.lockByCode(normalise(code))
            .orElseThrow(() -> new BusinessRuleException(
                DiscountRejection.NOT_FOUND.code(), DiscountRejection.NOT_FOUND.message()));

        validate(discount, subtotal, userId).ifPresent(reason -> {
            throw new BusinessRuleException(reason.code(), reason.messageFor(discount));
        });

        long amount = discount.discountFor(subtotal);

        discount.recordUse();
        discountCodeRepository.save(discount);
        discountRedemptionRepository.save(
            DiscountRedemption.of(discount.getId(), orderId, userId, amount));

        log.info("Redeemed discount {} on order {} for {} Rial ({} of {} uses spent)",
            discount.getCode(), orderId, amount, discount.getTimesUsed(), discount.getUsageLimit());

        return amount;
    }

    /**
     * Every reason this code cannot be used on this order, or empty if it can.
     *
     * <p>Shared verbatim by quote and redeem. Two copies of these rules would drift, and the way
     * they drift is that a shopper is quoted a discount and then charged full price.
     */
    private Optional<DiscountRejection> validate(DiscountCode discount, long subtotal, Long userId) {
        OffsetDateTime now = OffsetDateTime.now();

        if (!discount.isActive()) {
            return Optional.of(DiscountRejection.NOT_FOUND);
        }
        if (discount.getStartsAt() != null && discount.getStartsAt().isAfter(now)) {
            return Optional.of(DiscountRejection.NOT_STARTED);
        }
        if (discount.getEndsAt() != null && !discount.getEndsAt().isAfter(now)) {
            return Optional.of(DiscountRejection.EXPIRED);
        }
        if (!discount.hasUsesLeft()) {
            return Optional.of(DiscountRejection.EXHAUSTED);
        }
        if (subtotal < discount.getMinOrderTotal()) {
            return Optional.of(DiscountRejection.BELOW_MINIMUM);
        }
        if (userId != null && discount.getPerUserLimit() != null
            && discountRedemptionRepository.countByDiscountCodeIdAndUserId(discount.getId(), userId)
               >= discount.getPerUserLimit()) {
            return Optional.of(DiscountRejection.PER_USER_LIMIT);
        }
        if (discount.discountFor(subtotal) <= 0) {
            // An empty cart, or a percentage so small that integer division rounds it to nothing.
            // Spending a use of a limited code to take off zero Rial is worse than refusing it.
            return Optional.of(DiscountRejection.NO_EFFECT);
        }
        return Optional.empty();
    }

    // --- administration ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<DiscountCodeResponse> list(Pageable pageable) {
        return discountCodeRepository.findAllByOrderByCreatedAtDesc(pageable)
            .map(DiscountCodeResponse::from);
    }

    @Transactional(readOnly = true)
    public DiscountCodeResponse get(long id) {
        return DiscountCodeResponse.from(require(id));
    }

    @Transactional
    public DiscountCodeResponse create(DiscountCodeRequest request) {
        String code = normalise(request.code());
        requireSane(request);
        discountCodeRepository.findByCodeIgnoreCase(code).ifPresent(existing -> {
            throw new ConflictException("discount-code-taken",
                "A discount code '" + existing.getCode() + "' already exists.");
        });

        DiscountCode discount = DiscountCode.of(code, request.type(), request.value());
        apply(discount, request);

        return DiscountCodeResponse.from(discountCodeRepository.save(discount));
    }

    /**
     * Edits a code without touching what it has already done.
     *
     * <p>{@code timesUsed} and the redemption rows are left alone deliberately: they record
     * history. Raising a usage limit therefore grants more uses from where the code stands, which
     * is what an admin means by it; lowering it below what has been spent simply exhausts the code
     * rather than being rejected, which is the only sensible reading of "no more than five" said
     * after six.
     */
    @Transactional
    public DiscountCodeResponse update(long id, DiscountCodeRequest request) {
        DiscountCode discount = require(id);
        requireSane(request);

        String code = normalise(request.code());
        discountCodeRepository.findByCodeIgnoreCase(code)
            .filter(existing -> !existing.getId().equals(id))
            .ifPresent(existing -> {
                throw new ConflictException("discount-code-taken",
                    "A discount code '" + existing.getCode() + "' already exists.");
            });

        discount.setCode(code);
        discount.setType(request.type());
        discount.setValue(request.value());
        apply(discount, request);

        return DiscountCodeResponse.from(discountCodeRepository.save(discount));
    }

    /** Switches a code off. Never deleted — redemptions reference it and orders reference those. */
    @Transactional
    public DiscountCodeResponse deactivate(long id) {
        DiscountCode discount = require(id);
        discount.setActive(false);
        discount.touch();
        return DiscountCodeResponse.from(discountCodeRepository.save(discount));
    }

    private DiscountCode require(long id) {
        return discountCodeRepository.findById(id)
            .orElseThrow(() -> ResourceNotFoundException.of("Discount code", id));
    }

    private void apply(DiscountCode discount, DiscountCodeRequest request) {
        discount.setDescription(trimmed(request.description()));
        // A ceiling on a fixed amount is meaningless; storing one would only confuse the next
        // person to read the row.
        discount.setMaxDiscount(request.type() == DiscountType.PERCENTAGE
            ? request.maxDiscount() : null);
        discount.setMinOrderTotal(request.minOrderTotal());
        discount.setUsageLimit(request.usageLimit());
        discount.setPerUserLimit(request.perUserLimit());
        discount.setStartsAt(request.startsAt());
        discount.setEndsAt(request.endsAt());
        discount.setActive(request.isActive());
        discount.touch();
    }

    /**
     * The rules the database also enforces, checked here so an admin gets a readable message
     * instead of a constraint violation.
     */
    private void requireSane(DiscountCodeRequest request) {
        if (request.type() == DiscountType.PERCENTAGE && request.value() > 100) {
            throw new BusinessRuleException("discount-percentage-too-large",
                "A percentage discount cannot exceed 100.");
        }
        if (request.startsAt() != null && request.endsAt() != null
            && !request.endsAt().isAfter(request.startsAt())) {
            throw new BusinessRuleException("discount-window-invalid",
                "The end of the validity window must be after its start.");
        }
    }

    /**
     * Upper-cased and trimmed. Codes are printed on posters and typed by hand; storing "summer10"
     * and "SUMMER10" as two rows is how a campaign quietly stops working for half its audience.
     * The unique index on {@code LOWER(code)} is what makes that impossible rather than unlikely.
     */
    private String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
