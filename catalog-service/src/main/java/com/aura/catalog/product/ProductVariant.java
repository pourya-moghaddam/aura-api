package com.aura.catalog.product;

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
 * One buyable combination of a product — requirement 7's "variations of size and colour".
 *
 * <p>Both axes are nullable, and independently so: a phone has neither, a mug has a colour but no
 * size. That is why the uniqueness constraint has to be {@code NULLS NOT DISTINCT} — under the
 * default rule a product with no size could carry unlimited identical rows, because NULL never
 * equals NULL.
 */
@Entity
@Table(name = "product_variants")
@Getter
@Setter
@NoArgsConstructor
public class ProductVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "color_id")
    private Long colorId;

    @Column(name = "size_id")
    private Long sizeId;

    @Column(nullable = false, length = 100)
    private String sku;

    /** Rial, as an integer minor unit. Never a decimal — see the plan's money rule. */
    @Column(nullable = false)
    private Long price;

    /** Optional "was" price for showing a discount. Never used to charge. */
    @Column(name = "compare_at_price")
    private Long compareAtPrice;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static ProductVariant of(Long productId, Long colorId, Long sizeId, String sku,
                                    Long price, Long compareAtPrice) {
        ProductVariant variant = new ProductVariant();
        variant.productId = productId;
        variant.colorId = colorId;
        variant.sizeId = sizeId;
        variant.sku = sku;
        variant.price = price;
        variant.compareAtPrice = compareAtPrice;
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = variant.createdAt;
        return variant;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }
}
