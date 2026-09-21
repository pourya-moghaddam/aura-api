package com.aura.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {

    List<ProductVariant> findByProductIdOrderByIdAsc(Long productId);

    boolean existsBySku(String sku);

    Optional<ProductVariant> findBySku(String sku);

    /**
     * Finds a variant with the same colour/size combination, treating two NULLs as equal.
     *
     * <p>Plain {@code findByProductIdAndColorIdAndSizeId} cannot express this: SQL's NULL never
     * equals NULL, so a product with no size would report "no duplicate" for every row and the
     * conflict would only surface as a constraint violation from the database — a 500 rather than
     * a message naming the clash. This mirrors {@code UNIQUE NULLS NOT DISTINCT}.
     */
    @Query(value = """
        SELECT * FROM product_variants
        WHERE product_id = :productId
          AND color_id IS NOT DISTINCT FROM :colorId
          AND size_id IS NOT DISTINCT FROM :sizeId
        """, nativeQuery = true)
    Optional<ProductVariant> findByCombination(
        @Param("productId") Long productId,
        @Param("colorId") Long colorId,
        @Param("sizeId") Long sizeId
    );

    long countByProductIdAndIsActiveTrue(Long productId);
}
