package com.aura.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductFieldValueRepository
    extends JpaRepository<ProductFieldValue, ProductFieldValue.Key> {

    @Query("SELECT v FROM ProductFieldValue v WHERE v.key.productId = :productId")
    List<ProductFieldValue> findByProductId(@Param("productId") long productId);

    @Modifying
    @Query("DELETE FROM ProductFieldValue v WHERE v.key.productId = :productId")
    void deleteByProductId(@Param("productId") long productId);

    /**
     * The field and value slugs a product currently carries, for rebuilding the denormalised
     * {@code attributes} JSONB.
     *
     * <p>Returned as slug pairs rather than ids because the JSONB is keyed by slug — it is read by
     * the storefront and the search indexer, neither of which should have to resolve numeric ids
     * to build a filter URL.
     */
    @Query(value = """
        SELECT f.slug AS field_slug, fv.slug AS value_slug
        FROM product_field_values pfv
        JOIN fields f ON f.id = pfv.field_id
        JOIN field_values fv ON fv.id = pfv.field_value_id
        WHERE pfv.product_id = :productId
        ORDER BY f.sort_order, f.slug, fv.sort_order, fv.slug
        """, nativeQuery = true)
    List<SlugPair> findSlugPairs(@Param("productId") long productId);

    /** Projection for {@link #findSlugPairs}. */
    interface SlugPair {
        String getFieldSlug();

        String getValueSlug();
    }
}
