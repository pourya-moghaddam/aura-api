package com.aura.order.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * What order-service needs from catalog.
 *
 * <p>An interface so cart and checkout rules can be tested without a catalog on the other end, and
 * so the current synchronous calls could be replaced — by a cache, or by a local projection fed
 * from {@code ProductChanged} — without touching anything that uses it.
 */
public interface CatalogGateway {

    /**
     * @return snapshots keyed by variant id. Ids that no longer exist are simply absent, which is
     *         how the caller learns a variant was deleted rather than merely withdrawn.
     */
    Map<Long, VariantSnapshot> snapshotsFor(Collection<Long> variantIds);

    /**
     * Holds stock for an order that is about to be paid for.
     *
     * <p>Idempotent on {@code orderId}: reserving twice for one order returns the existing hold
     * rather than taking the stock again, because a retried checkout must not consume double.
     *
     * @throws com.aura.common.web.error.BusinessRuleException if the stock is not there, or if
     *         catalog cannot be reached — both mean the order must not be placed
     */
    void reserveStock(long orderId, List<StockLine> lines);

    /** Payment failed or the order was abandoned: the units go back on the shelf. */
    void releaseStock(long orderId);

    /** Payment cleared: the units leave stock for good. */
    void commitStock(long orderId);

    /** One line of a reservation, in catalog's terms rather than the cart's. */
    record StockLine(Long variantId, Integer quantity) {
    }
}
