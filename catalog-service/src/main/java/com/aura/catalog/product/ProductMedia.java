package com.aura.catalog.product;

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

import java.util.UUID;

/**
 * A product's link to a file in media-service.
 *
 * <p>Only the id is stored. media-service owns the bytes, the buckets, and the judgement of whether
 * a file is servable at all — duplicating any of that here would mean two answers to "is this
 * image safe to show" that could disagree.
 */
@Entity
@Table(name = "product_media")
@Getter
@Setter
@NoArgsConstructor
public class ProductMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MediaKind kind = MediaKind.IMAGE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * The image used in listings and search results. A partial unique index enforces at most one
     * per product, so this cannot drift into two products' worth of primaries by accident.
     */
    @Column(name = "is_primary", nullable = false)
    private boolean isPrimary;

    public static ProductMedia of(Long productId, UUID mediaId, MediaKind kind, int sortOrder,
                                  boolean isPrimary) {
        ProductMedia media = new ProductMedia();
        media.productId = productId;
        media.mediaId = mediaId;
        media.kind = kind;
        media.sortOrder = sortOrder;
        media.isPrimary = isPrimary;
        return media;
    }

    /** Mirrors the {@code ck_product_media_kind} check constraint. */
    public enum MediaKind {
        IMAGE,
        VIDEO
    }
}
