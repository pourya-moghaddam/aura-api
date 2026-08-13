package com.aura.catalog.product;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Everything order-service needs to know about a variant in order to sell it.
 *
 * <p>One query rather than several endpoints because the caller always wants the whole picture at
 * once: a cart line has to show a name and a price, and checkout additionally needs the seller and
 * the live stock. Splitting it would turn one round trip per cart into four.
 *
 * <p>A read-only {@link Repository} rather than a {@code JpaRepository}: there is no entity here
 * and nothing to write — exposing save and delete for a projection would be an invitation.
 */
public interface VariantSnapshotRepository extends Repository<ProductVariant, Long> {

    /**
     * @param variantIds may contain ids that do not exist; they are simply absent from the result,
     *                   which is how the caller learns a variant has been deleted.
     */
    @Query(value = """
        SELECT v.id                AS variantId,
               v.product_id        AS productId,
               p.seller_id         AS sellerId,
               p.name              AS productName,
               p.slug              AS productSlug,
               p.status            AS productStatus,
               c.name              AS colorName,
               s.name              AS sizeName,
               v.price             AS unitPrice,
               v.is_active         AS variantActive,
               -- What is buyable now: on hand minus what other unpaid orders are holding. A
               -- variant with no inventory row was never stocked, which reads the same as zero.
               COALESCE(i.quantity_on_hand, 0) - COALESCE(i.quantity_reserved, 0) AS available
        FROM product_variants v
        JOIN products p ON p.id = v.product_id
        LEFT JOIN colors c ON c.id = v.color_id
        LEFT JOIN sizes s ON s.id = v.size_id
        LEFT JOIN inventory i ON i.variant_id = v.id
        WHERE v.id IN (:variantIds)
        """, nativeQuery = true)
    List<VariantSnapshot> findSnapshots(@Param("variantIds") Collection<Long> variantIds);

    /**
     * Projection. {@code productStatus} and {@code variantActive} travel rather than a single
     * "buyable" flag so the caller can say <em>why</em> a line cannot be bought — "no longer sold"
     * reads differently to "out of stock", and a shopper deserves the difference.
     */
    interface VariantSnapshot {
        Long getVariantId();

        Long getProductId();

        Long getSellerId();

        String getProductName();

        String getProductSlug();

        String getProductStatus();

        String getColorName();

        String getSizeName();

        Long getUnitPrice();

        Boolean getVariantActive();

        Integer getAvailable();
    }
}
