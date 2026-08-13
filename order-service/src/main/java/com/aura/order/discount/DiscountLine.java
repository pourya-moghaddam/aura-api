package com.aura.order.discount;

import java.util.List;

/**
 * One basket line, in the only terms a discount cares about.
 *
 * <p>Deliberately not a cart item or an order item: the same rules are applied to both, and to a
 * seller-composed order that is neither. What all three can supply is what a line is worth and
 * where it sits in the catalogue.
 *
 * @param categoryPath the line's category and every ancestor of it, so a code scoped to a parent
 *                     category matches without this service holding a copy of the tree
 * @param lineTotal    Rial, quantity included
 */
public record DiscountLine(
    Long productId,
    Long categoryId,
    List<Long> categoryPath,
    long lineTotal
) {

    public DiscountLine {
        categoryPath = categoryPath == null ? List.of() : List.copyOf(categoryPath);
    }
}
