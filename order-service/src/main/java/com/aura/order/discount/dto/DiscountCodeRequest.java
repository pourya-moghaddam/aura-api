package com.aura.order.discount.dto;

import com.aura.order.discount.DiscountScope;
import com.aura.order.discount.DiscountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * @param value       percent for {@code PERCENTAGE}, Rial for {@code FIXED}
 * @param maxDiscount ceiling on a percentage discount; ignored for a fixed one
 * @param usageLimit  null for unlimited
 */
public record DiscountCodeRequest(
    @NotBlank(message = "Code is required")
    @Size(max = 50)
    String code,

    String description,

    @NotNull(message = "Type is required")
    DiscountType type,

    @NotNull(message = "Value is required")
    @Positive(message = "Value must be greater than zero")
    Long value,

    @PositiveOrZero(message = "Maximum discount cannot be negative")
    Long maxDiscount,

    @PositiveOrZero(message = "Minimum order total cannot be negative")
    Long minOrderTotal,

    @Positive(message = "Usage limit must be at least one")
    Integer usageLimit,

    @Positive(message = "Per-user limit must be at least one")
    Integer perUserLimit,

    OffsetDateTime startsAt,

    OffsetDateTime endsAt,

    Boolean isActive,

    /** What the code applies to. Defaults to the whole order, which is what every code was before. */
    DiscountScope scope,

    /** Category or product ids, depending on the scope. Empty for an order-wide code. */
    List<Long> scopeIds
) {

    /** Boxed and defaulted — a primitive component would make these silently mandatory. */
    public DiscountCodeRequest {
        if (minOrderTotal == null) {
            minOrderTotal = 0L;
        }
        if (isActive == null) {
            isActive = true;
        }
        if (scope == null) {
            scope = DiscountScope.ORDER;
        }
        scopeIds = scopeIds == null ? List.of() : List.copyOf(scopeIds);
    }
}
