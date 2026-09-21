package com.aura.catalog.field;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FieldRepository extends JpaRepository<Field, Long> {

    List<Field> findByCategoryIdOrderBySortOrderAscNameAsc(Long categoryId);

    /**
     * Every field that applies to a category: those attached to it, plus those attached to any
     * ancestor.
     *
     * <p>{@code @>} is ancestor-or-equal in ltree, so the category's own fields are included
     * without a separate clause. Ordering puts broader fields first — a field defined on "Clothing"
     * sorts above one defined on "Shirts" at equal sort_order — so the seller's form reads
     * general-to-specific rather than in whatever order the rows happen to come back.
     */
    @Query(value = """
        SELECT f.* FROM fields f
        JOIN categories fc ON fc.id = f.category_id
        WHERE fc.path @> (SELECT c.path FROM categories c WHERE c.id = :categoryId)
        ORDER BY nlevel(fc.path), f.sort_order, f.name
        """, nativeQuery = true)
    List<Field> findEffectiveForCategory(@Param("categoryId") Long categoryId);

    /**
     * Looks for a conflicting slug anywhere on the category's ancestry line — ancestors, itself, and
     * descendants.
     *
     * <p>The {@code uq_fields_slug_per_category} constraint is not enough on its own. Because a
     * leaf's effective field set is the union over all its ancestors, defining "material" on both
     * "Clothing" and "Clothing &gt; Men" gives a Men product two fields with the same slug: the
     * seller's form shows the attribute twice and a filter URL naming that slug becomes ambiguous.
     * Neither category violates the per-category constraint, so this has to be checked here.
     *
     * <p>Descendants are included because the collision is symmetric — defining the field on the
     * parent afterwards is just as broken as defining it on the child.
     */
    @Query(value = """
        SELECT f.* FROM fields f
        JOIN categories fc ON fc.id = f.category_id
        WHERE LOWER(f.slug) = LOWER(:slug)
          AND (fc.path @> (SELECT c.path FROM categories c WHERE c.id = :categoryId)
               OR fc.path <@ (SELECT c.path FROM categories c WHERE c.id = :categoryId))
        LIMIT 1
        """, nativeQuery = true)
    Optional<Field> findConflictingSlugOnAncestryLine(
        @Param("slug") String slug,
        @Param("categoryId") Long categoryId
    );

    @Query(value = "SELECT EXISTS (SELECT 1 FROM product_field_values WHERE field_id = :fieldId)",
        nativeQuery = true)
    boolean isUsedByAnyProduct(@Param("fieldId") Long fieldId);
}
