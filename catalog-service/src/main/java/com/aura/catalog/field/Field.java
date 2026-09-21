package com.aura.catalog.field;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A custom attribute an admin defines for a category — "Material", "Sleeve length", "Screen size".
 *
 * <p>Fields are inherited downward: one attached to "Clothing" applies to every product under
 * "Clothing &gt; Men &gt; Shirts" without being redefined there. That is the whole point of hanging
 * them off a category rather than off a product, and it is why {@code category_id} is NOT NULL —
 * there is no such thing as a global field. An attribute that genuinely applies to everything
 * belongs on the root category.
 */
@Entity
@Table(name = "fields")
@Getter
@Setter
@NoArgsConstructor
public class Field {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The category this field is attached to. It applies here and everywhere beneath. */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 100)
    private String name;

    /**
     * Stable identifier used in filter query strings, so it is restricted to URL-safe characters
     * and must stay unique across the whole ancestry line rather than merely per category — see
     * {@link FieldService}.
     */
    @Column(nullable = false, length = 120)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private FieldDataType dataType = FieldDataType.SELECT;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired;

    /** Whether the storefront sidebar offers this as a facet. */
    @Column(name = "is_filterable", nullable = false)
    private boolean isFilterable = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public static Field of(Long categoryId, String name, String slug, FieldDataType dataType) {
        Field field = new Field();
        field.categoryId = categoryId;
        field.name = name;
        field.slug = slug;
        field.dataType = dataType;
        field.createdAt = OffsetDateTime.now();
        return field;
    }
}
