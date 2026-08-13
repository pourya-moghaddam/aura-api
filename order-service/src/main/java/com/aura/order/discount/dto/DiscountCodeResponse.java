package com.aura.order.discount.dto;

import com.aura.order.discount.DiscountCode;
import com.aura.order.discount.DiscountScope;
import com.aura.order.discount.DiscountType;

import java.time.OffsetDateTime;

/** The admin view: everything, including how much of the code has been spent. */
public record DiscountCodeResponse(
    Long id,
    String code,
    String description,
    DiscountType type,
    Long value,
    Long maxDiscount,
    Long minOrderTotal,
    DiscountScope scope,
    java.util.List<Long> scopeIds,
    Integer usageLimit,
    Integer perUserLimit,
    int timesUsed,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    boolean isActive,
    OffsetDateTime createdAt
) {

    public static DiscountCodeResponse from(DiscountCode discount) {
        return new DiscountCodeResponse(discount.getId(), discount.getCode(),
            discount.getDescription(), discount.getType(), discount.getValue(),
            discount.getMaxDiscount(), discount.getMinOrderTotal(),
            discount.getScope(), java.util.List.copyOf(discount.scopeIdSet()),
            discount.getUsageLimit(),
            discount.getPerUserLimit(), discount.getTimesUsed(), discount.getStartsAt(),
            discount.getEndsAt(), discount.isActive(), discount.getCreatedAt());
    }
}
