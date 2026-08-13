package com.aura.order.cart.dto;

import com.aura.order.cart.Cart;
import com.aura.order.cart.CartItem;
import com.aura.order.catalog.VariantSnapshot;

import java.util.List;
import java.util.Map;

/**
 * A cart, priced as it stands right now.
 *
 * @param subtotal the sum of current prices, not of what things cost when they were added. What
 *                 the shopper will actually be charged, so it has to be the live figure.
 * @param hasIssues whether any line needs attention before checkout — something withdrawn, out of
 *                 stock, or repriced. Lets the cart page say so without the client re-deriving it.
 */
public record CartResponse(
    List<CartLine> items,
    long subtotal,
    int itemCount,
    boolean hasIssues
) {

    public static CartResponse empty() {
        return new CartResponse(List.of(), 0L, 0, false);
    }

    public static CartResponse of(Cart cart, List<CartItem> items,
                                  Map<Long, VariantSnapshot> snapshots) {
        List<CartLine> lines = items.stream()
            .map(item -> CartLine.of(item, snapshots.get(item.getVariantId())))
            .toList();

        long subtotal = lines.stream()
            .filter(CartLine::available)
            .mapToLong(CartLine::lineTotal)
            .sum();

        return new CartResponse(
            lines,
            subtotal,
            lines.stream().mapToInt(CartLine::quantity).sum(),
            lines.stream().anyMatch(line -> !line.available() || line.priceChanged()));
    }

    /**
     * @param unitPrice     the price now, which is what checkout will use
     * @param priceAtAdd    what it cost when it went in the cart, kept only so the shopper can be
     *                      told it moved
     * @param priceChanged  true when those two differ
     * @param available     whether this line can still be bought at this quantity
     * @param unavailableReason why not, when it cannot — "no longer sold" and "only 2 left" are
     *                      different problems with different remedies
     */
    public record CartLine(
        Long variantId,
        Long productId,
        String productName,
        String productSlug,
        String colorName,
        String sizeName,
        int quantity,
        Long unitPrice,
        Long priceAtAdd,
        boolean priceChanged,
        long lineTotal,
        boolean available,
        String unavailableReason
    ) {

        static CartLine of(CartItem item, VariantSnapshot snapshot) {
            if (snapshot == null) {
                // The variant was deleted outright while it sat in the cart. Everything but the
                // ids is gone, so the line shows what it can and is marked unbuyable.
                return new CartLine(item.getVariantId(), item.getProductId(), null, null, null, null,
                    item.getQuantity(), null, item.getPriceAtAdd(), false, 0L,
                    false, "This item is no longer sold.");
            }

            boolean purchasable = snapshot.purchasable();
            boolean inStock = snapshot.available() >= item.getQuantity();
            String reason = !purchasable
                ? "This item is no longer sold."
                : inStock ? null
                : snapshot.available() <= 0 ? "Out of stock."
                : "Only " + snapshot.available() + " left in stock.";

            return new CartLine(
                item.getVariantId(), item.getProductId(),
                snapshot.productName(), snapshot.productSlug(),
                snapshot.colorName(), snapshot.sizeName(),
                item.getQuantity(),
                snapshot.unitPrice(), item.getPriceAtAdd(),
                !snapshot.unitPrice().equals(item.getPriceAtAdd()),
                snapshot.unitPrice() * item.getQuantity(),
                purchasable && inStock, reason);
        }
    }
}
