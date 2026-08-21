package com.aura.catalog.inventory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Answers "can this variant be bought", for read paths that need to say so.
 *
 * <p>Separate from {@link InventoryService} to avoid a circular dependency, not for tidiness:
 * {@code InventoryService} injects {@code ProductService} (it refreshes the product's derived
 * fields after a stock change), so having {@code ProductService} inject it back would be a cycle
 * Spring refuses at startup. This component depends on the repository alone, so either service can
 * use it.
 *
 * <p>Deliberately returns a <em>set of buyable variant ids</em> rather than quantities. The
 * storefront needs to know whether to disable a size, not how many are in the warehouse, and
 * publishing exact counts tells competitors precisely how the shop is doing.
 */
@Component
@RequiredArgsConstructor
public class StockAvailability {

    private final InventoryRepository inventoryRepository;

    /**
     * Of the given variants, those a shopper may currently buy.
     *
     * <p>One query for the whole set. A product page with five sizes in three colours is fifteen
     * variants, and asking per variant would be fifteen round trips to render one page.
     *
     * <p>A variant with no inventory row is absent from the result — it has never been stocked, so
     * treating it as unavailable is both correct and the safe direction to be wrong in.
     */
    @Transactional(readOnly = true)
    public Set<Long> buyableAmong(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) {
            // `IN ()` is a syntax error in Postgres, and Spring Data would happily build one.
            return Set.of();
        }

        return inventoryRepository.findByVariantIdIn(variantIds).stream()
            .filter(inventory -> inventory.available() > 0)
            .map(Inventory::getVariantId)
            .collect(Collectors.toSet());
    }

    /** Convenience for a single variant, in terms of the same rule. */
    @Transactional(readOnly = true)
    public boolean isBuyable(long variantId) {
        return !buyableAmong(Set.of(variantId)).isEmpty();
    }

    /**
     * Availability keyed by variant id, for callers mapping a list of variants.
     *
     * <p>Every requested id appears in the map, so a caller can index it without deciding what an
     * absent key means — which is exactly the decision that produces an accidental "available".
     */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> byVariant(Collection<Long> variantIds) {
        Set<Long> buyable = buyableAmong(variantIds);
        return variantIds.stream()
            .distinct()
            .collect(Collectors.toMap(Function.identity(), buyable::contains));
    }
}
