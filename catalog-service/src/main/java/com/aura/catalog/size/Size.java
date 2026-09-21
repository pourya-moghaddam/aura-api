package com.aura.catalog.size;

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
 * A size a seller can pick when defining a variant.
 *
 * <p>Replaces the free-text {@code size_name} the original schema had. Typed sizes make "XL", "xl"
 * and "X-Large" three separate values, which is invisible until the storefront tries to offer size
 * as a filter and shows all three as distinct options.
 */
@Entity
@Table(name = "sizes")
@Getter
@Setter
@NoArgsConstructor
public class Size {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /**
     * Optional scope. Shoe sizes (38, 39, 40) and shirt sizes (S, M, L) should not appear in one
     * list, so a size can be attached to a category and offered only beneath it. NULL means global.
     */
    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public static Size of(String name, Long categoryId, int sortOrder) {
        Size size = new Size();
        size.name = name;
        size.categoryId = categoryId;
        size.sortOrder = sortOrder;
        size.createdAt = OffsetDateTime.now();
        return size;
    }
}
