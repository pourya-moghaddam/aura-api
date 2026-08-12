package com.aura.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductMediaRepository extends JpaRepository<ProductMedia, Long> {

    List<ProductMedia> findByProductIdOrderBySortOrderAscIdAsc(Long productId);

    List<ProductMedia> findByProductIdInOrderBySortOrderAscIdAsc(List<Long> productIds);

    Optional<ProductMedia> findByProductIdAndMediaId(Long productId, UUID mediaId);

    /**
     * Clears the primary flag across a product's media.
     *
     * <p>Needed as a separate statement because {@code uq_product_media_one_primary} is a unique
     * index: setting a new primary before clearing the old one violates it mid-transaction.
     */
    @Modifying
    @Query("UPDATE ProductMedia m SET m.isPrimary = false WHERE m.productId = :productId")
    void clearPrimary(@Param("productId") Long productId);

    long countByProductId(Long productId);
}
