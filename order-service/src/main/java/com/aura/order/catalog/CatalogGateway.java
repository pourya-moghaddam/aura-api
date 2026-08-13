package com.aura.order.catalog;

import java.util.Collection;
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
}
