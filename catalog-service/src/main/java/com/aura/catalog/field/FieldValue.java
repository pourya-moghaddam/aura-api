package com.aura.catalog.field;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * One allowed value of a SELECT or MULTI_SELECT field — "Cotton" for "Material".
 *
 * <p>Enumerated rather than free text so that the storefront can offer the field as a facet. Free
 * text would make "Cotton", "cotton" and "100% cotton" three separate filter options, which is
 * invisible until the sidebar is built and then very expensive to undo.
 */
@Entity
@Table(name = "field_values")
@Getter
@Setter
@NoArgsConstructor
public class FieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    @Column(nullable = false, length = 150)
    private String value;

    /** Appears in filter query strings, so it is URL-safe and unique within the field. */
    @Column(nullable = false, length = 170)
    private String slug;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public static FieldValue of(Long fieldId, String value, String slug, int sortOrder) {
        FieldValue fieldValue = new FieldValue();
        fieldValue.fieldId = fieldId;
        fieldValue.value = value;
        fieldValue.slug = slug;
        fieldValue.sortOrder = sortOrder;
        fieldValue.createdAt = OffsetDateTime.now();
        return fieldValue;
    }
}
