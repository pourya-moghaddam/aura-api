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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A seller's product — requirement 7.
 *
 * <p>Prices and stock live on variants, not here. The three denormalised columns below are copies
 * maintained on write so that sorting a category page by price, or filtering to in-stock, does not
 * need an aggregate over every variant of every product on the page.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The owning seller's user id in auth-service. No foreign key — separate service, separate
     * database — so nothing at the database level stops a bad value; the service is the guarantee.
     */
    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 275)
    private String slug;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status = ProductStatus.DRAFT;

    /** Rial, from the cheapest active variant. Null while the product has no active variants. */
    @Column(name = "min_price")
    private Long minPrice;

    @Column(name = "max_price")
    private Long maxPrice;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    /**
     * Derived from {@code product_field_values} and rebuilt on every write — never edited directly.
     *
     * <p>Keyed by field slug, holding the list of chosen value slugs. It exists so the product page
     * and the search indexer read one row instead of a join per field. The normalised table stays
     * the source of truth because JSONB cannot carry a foreign key, and without one nothing stops a
     * seller inventing a value that was never on the admin's list.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, List<String>> attributes = new LinkedHashMap<>();

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    public static Product draft(long sellerId, long categoryId, String name, String slug,
                                String description) {
        Product product = new Product();
        product.sellerId = sellerId;
        product.categoryId = categoryId;
        product.name = name;
        product.slug = slug;
        product.description = description;
        product.status = ProductStatus.DRAFT;
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = product.createdAt;
        return product;
    }

    public boolean isOwnedBy(long userId) {
        return sellerId != null && sellerId == userId;
    }

    public void touch() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Set once, on the first publish. A product taken down and put back up keeps its original
     * date — "new arrivals" should mean when shoppers first saw it, not when it was last toggled.
     */
    public void publish() {
        this.status = ProductStatus.ACTIVE;
        if (this.publishedAt == null) {
            this.publishedAt = OffsetDateTime.now();
        }
        touch();
    }

    public void archive() {
        this.status = ProductStatus.ARCHIVED;
        touch();
    }
}
