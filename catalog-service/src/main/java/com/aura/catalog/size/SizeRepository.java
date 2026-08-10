package com.aura.catalog.size;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SizeRepository extends JpaRepository<Size, Long> {

    List<Size> findAllByOrderBySortOrderAscNameAsc();

    /**
     * Sizes offered for a category: those scoped to one of its ancestors, plus every global size.
     *
     * <p>Uses the same ltree ancestor containment as field inheritance — a size attached to
     * "Clothing" is available in "Clothing &gt; Men &gt; Shirts" without being redefined there.
     */
    @Query(value = """
        SELECT s.* FROM sizes s
        WHERE s.is_active = TRUE
          AND (s.category_id IS NULL
               OR s.category_id IN (
                   SELECT a.id FROM categories a
                   WHERE a.path @> (SELECT c.path FROM categories c WHERE c.id = :categoryId)
               ))
        ORDER BY s.sort_order, s.name
        """, nativeQuery = true)
    List<Size> findAvailableForCategory(@Param("categoryId") Long categoryId);

    List<Size> findByIsActiveTrueOrderBySortOrderAscNameAsc();

    /**
     * Name uniqueness is per scope, matching the {@code UNIQUE NULLS NOT DISTINCT (name,
     * category_id)} constraint — "L" may exist once globally and once under a specific category.
     */
    @Query("""
        SELECT s FROM Size s
        WHERE LOWER(s.name) = LOWER(:name)
          AND ((:categoryId IS NULL AND s.categoryId IS NULL) OR s.categoryId = :categoryId)
        """)
    Optional<Size> findByNameAndScope(@Param("name") String name, @Param("categoryId") Long categoryId);

    @Query(value = "SELECT EXISTS (SELECT 1 FROM product_variants WHERE size_id = :sizeId)",
        nativeQuery = true)
    boolean isUsedByAnyVariant(@Param("sizeId") Long sizeId);
}
