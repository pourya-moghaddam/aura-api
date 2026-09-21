package com.aura.catalog.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    List<Inventory> findByVariantIdIn(Collection<Long> variantIds);

    /**
     * Locks the inventory rows for a set of variants, in a deterministic order.
     *
     * <p>{@code FOR UPDATE} is what makes overselling impossible: a second transaction asking for
     * the same rows blocks until this one commits, so both cannot read "1 available" and both
     * decide to sell it. A plain read-then-write, however carefully written in Java, always has a
     * window between the two.
     *
     * <p>{@code ORDER BY variant_id} is not cosmetic. Two orders containing the same two variants
     * in opposite cart order would otherwise each lock one row and wait forever for the other —
     * a deadlock PostgreSQL resolves by killing one of them, so it surfaces as a random checkout
     * failure under load and never in testing. Locking in a consistent order removes the cycle.
     *
     * <p>Deliberately not {@code SKIP LOCKED}: skipping a contended row here would silently reserve
     * part of a cart. Blocking and then seeing the true figure is the correct behaviour.
     */
    @Query(value = """
        SELECT * FROM inventory
        WHERE variant_id IN (:variantIds)
        ORDER BY variant_id
        FOR UPDATE
        """, nativeQuery = true)
    List<Inventory> lockForUpdate(@Param("variantIds") Collection<Long> variantIds);

    @Query(value = "SELECT * FROM inventory WHERE variant_id = :variantId FOR UPDATE",
        nativeQuery = true)
    Optional<Inventory> lockOne(@Param("variantId") Long variantId);
}
