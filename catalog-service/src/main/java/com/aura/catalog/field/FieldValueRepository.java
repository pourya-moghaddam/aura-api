package com.aura.catalog.field;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FieldValueRepository extends JpaRepository<FieldValue, Long> {

    List<FieldValue> findByFieldIdOrderBySortOrderAscValueAsc(Long fieldId);

    List<FieldValue> findByFieldIdInOrderBySortOrderAscValueAsc(List<Long> fieldIds);

    @Query("SELECT v FROM FieldValue v WHERE v.fieldId = :fieldId AND LOWER(v.slug) = LOWER(:slug)")
    Optional<FieldValue> findByFieldIdAndSlug(
        @Param("fieldId") Long fieldId,
        @Param("slug") String slug
    );

    @Query("SELECT v FROM FieldValue v WHERE v.fieldId = :fieldId AND LOWER(v.value) = LOWER(:value)")
    Optional<FieldValue> findByFieldIdAndValue(
        @Param("fieldId") Long fieldId,
        @Param("value") String value
    );

    @Query(value = """
        SELECT EXISTS (SELECT 1 FROM product_field_values WHERE field_value_id = :fieldValueId)
        """, nativeQuery = true)
    boolean isUsedByAnyProduct(@Param("fieldValueId") Long fieldValueId);
}
