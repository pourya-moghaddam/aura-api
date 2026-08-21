package com.aura.catalog.product;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Product> findBySellerIdOrderByUpdatedAtDesc(Long sellerId, Pageable pageable);

    Page<Product> findByStatusOrderByCreatedAtDesc(ProductStatus status, Pageable pageable);

    /**
     * Active products in a category or anywhere beneath it — the storefront's category page,
     * requirement 13.
     *
     * <p>One indexed ltree containment scan rather than a recursive CTE per page view. Browsing
     * "Clothing" has to show the shirts filed under "Clothing &gt; Men &gt; Shirts", or the tree is
     * decorative.
     */
    @Query(value = """
        SELECT p.* FROM products p
        JOIN categories c ON c.id = p.category_id
        WHERE p.status = 'ACTIVE'
          AND c.path <@ (SELECT root.path FROM categories root WHERE root.id = :categoryId)
        """,
        countQuery = """
            SELECT COUNT(*) FROM products p
            JOIN categories c ON c.id = p.category_id
            WHERE p.status = 'ACTIVE'
              AND c.path <@ (SELECT root.path FROM categories root WHERE root.id = :categoryId)
            """,
        nativeQuery = true)
    Page<Product> findActiveInCategoryTree(@Param("categoryId") Long categoryId, Pageable pageable);

    /**
     * Recomputes the denormalised price range and stock total from the product's own variants.
     *
     * <p>Done in SQL rather than by loading variants and summing in Java: this runs after every
     * variant and stock change, and the aggregate is exactly what the database is for. Only active
     * variants count towards price — an inactive one should not set the "from" price on a listing
     * a shopper cannot actually buy at.
     *
     * <p><strong>{@code clearAutomatically} is load-bearing, not tidiness.</strong> This is a bulk
     * UPDATE issued straight to the database, so it bypasses the persistence context. Without
     * clearing, the {@code findById} that follows in {@code ProductService#refreshDerivedFields} is
     * answered from JPA's first-level cache and returns the entity as it was <em>before</em> this
     * ran — old price, old stock, and an old {@code updated_at}.
     *
     * <p>That last one is what made it dangerous. {@code updated_at} becomes the external version
     * on the Elasticsearch document, so the event published afterwards carried a version equal to
     * the one already indexed, and Elasticsearch discarded it as a stale redelivery — silently, by
     * design. The effect was that <em>stock and price changes never reached the search index at
     * all</em>: a product that sold out went on being listed as in stock until some unrelated edit
     * happened to touch the entity through JPA.
     *
     * <p>{@code flushAutomatically} is the matching half — pending changes are written before this
     * statement runs, so the aggregate is computed over current state rather than stale state.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        UPDATE products p SET
            min_price = agg.min_price,
            max_price = agg.max_price,
            total_stock = COALESCE(agg.total_stock, 0),
            updated_at = NOW()
        FROM (
            SELECT
                MIN(v.price) FILTER (WHERE v.is_active) AS min_price,
                MAX(v.price) FILTER (WHERE v.is_active) AS max_price,
                SUM(COALESCE(i.quantity_on_hand, 0) - COALESCE(i.quantity_reserved, 0))
                    FILTER (WHERE v.is_active) AS total_stock
            FROM product_variants v
            LEFT JOIN inventory i ON i.variant_id = v.id
            WHERE v.product_id = :productId
        ) agg
        WHERE p.id = :productId
        """, nativeQuery = true)
    void recomputeDerivedFields(@Param("productId") Long productId);
}
