package com.aura.catalog.size;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SizeRepository extends JpaRepository<Size, Long> {

    List<Size> findAllByOrderBySortOrderAscNameAsc();

    /**
     * Sizes offered for a category: those scoped to one of its ancestors, plus every global size —
     * with the most specific definition of a given name shadowing the broader ones.
     *
     * <p>Uses the same ltree ancestor containment as field inheritance, so a size attached to
     * "Clothing" is available under "Clothing &gt; Men &gt; Shirts" without being redefined there.
     *
     * <p>Shadowing is what makes redefining one worth doing. A global "L" and an "L" scoped to
     * Clothing both apply to a shirt, and returning both put the same label in the seller's picker
     * twice with nothing to tell them apart — while defining the narrower one is precisely how an
     * admin says "L means something different here". Ranking by {@code nlevel} of the scoping
     * category's path takes the deepest, with global counting as zero. The id tiebreak only ever
     * matters for two same-named sizes in one scope, which the unique constraint already prevents.
     */
    @Query(value = """
        SELECT ranked.id, ranked.name, ranked.category_id, ranked.sort_order,
               ranked.is_active, ranked.created_at
        FROM (
            SELECT s.*,
                   ROW_NUMBER() OVER (
                       PARTITION BY LOWER(s.name)
                       ORDER BY COALESCE(nlevel(sc.path), 0) DESC, s.id
                   ) AS specificity_rank
            FROM sizes s
            LEFT JOIN categories sc ON sc.id = s.category_id
            WHERE s.is_active = TRUE
              AND (s.category_id IS NULL
                   OR s.category_id IN (
                       SELECT a.id FROM categories a
                       WHERE a.path @> (SELECT c.path FROM categories c WHERE c.id = :categoryId)
                   ))
        ) ranked
        WHERE ranked.specificity_rank = 1
        ORDER BY ranked.sort_order, ranked.name
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
