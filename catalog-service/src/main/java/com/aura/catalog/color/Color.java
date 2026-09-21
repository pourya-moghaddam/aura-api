package com.aura.catalog.color;

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
 * A colour a seller can pick when defining a variant — requirement 3.
 *
 * <p>Admin-defined rather than free text so the storefront's colour filter has a fixed, correct set
 * of buckets. If sellers typed the name, "Navy", "navy" and "Dark Blue" become three filters for
 * one colour.
 */
@Entity
@Table(name = "colors")
@Getter
@Setter
@NoArgsConstructor
public class Color {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** {@code #RRGGBB}. Validated in the DTO and again by a database CHECK. */
    @Column(name = "hex_code", nullable = false, length = 7)
    private String hexCode;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * Deactivating rather than deleting is the usual path: a colour in use by an existing variant
     * cannot be deleted, but it can be retired so nobody picks it again.
     */
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public static Color of(String name, String hexCode, int sortOrder) {
        Color color = new Color();
        color.name = name;
        color.hexCode = hexCode;
        color.sortOrder = sortOrder;
        color.createdAt = OffsetDateTime.now();
        return color;
    }
}
