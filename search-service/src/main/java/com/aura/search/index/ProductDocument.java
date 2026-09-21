package com.aura.search.index;

import com.aura.common.events.ProductChangedEvent;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A product as the index holds it.
 *
 * <p>Separate from {@link ProductChangedEvent} on purpose, even though the two overlap almost
 * entirely today. The event is a contract with catalog and changing it is a coordinated release;
 * the document is this service's own storage and changing it is a reindex. Collapsing them would
 * mean every indexing decision — a derived field, a differently-shaped facet — became a change to
 * a published event.
 *
 * @param inStock derived here rather than sent, because "can I buy this" is a search concern and
 *                catalog has no opinion about how a shopper filters
 */
public record ProductDocument(
    Long productId,
    Long sellerId,
    Long categoryId,
    List<Long> categoryPath,
    List<String> categoryNames,
    String name,
    String slug,
    String description,
    String status,
    Long minPrice,
    Long maxPrice,
    /** Pre-sale price of the variant behind {@code minPrice}; null when it is not discounted. */
    Long compareAtPrice,
    Integer totalStock,
    boolean inStock,
    List<String> colorNames,
    List<String> sizeNames,
    Map<String, List<String>> attributes,
    String primaryMediaId,

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant createdAt,

    /*
     * ISO-8601, pinned rather than left to the mapper. Jackson's default for an Instant is a
     * decimal epoch, which Elasticsearch rejects outright for a date field - and the only reason
     * that is not a production outage is that Spring Boot happens to configure the mapper the
     * other way. The index format should not depend on a global setting somebody may change for
     * an unrelated reason.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant updatedAt
) {

    public static ProductDocument from(ProductChangedEvent event) {
        return new ProductDocument(
            event.productId(),
            event.sellerId(),
            event.categoryId(),
            event.categoryPath(),
            event.categoryNames(),
            event.name(),
            event.slug(),
            event.description(),
            event.status(),
            event.minPrice(),
            event.maxPrice(),
            event.compareAtPrice(),
            event.totalStock(),
            event.totalStock() != null && event.totalStock() > 0,
            event.colorNames(),
            event.sizeNames(),
            event.attributes(),
            event.primaryMediaId() == null ? null : event.primaryMediaId().toString(),
            event.createdAt(),
            event.occurredAt());
    }

    /** Elasticsearch's document id. The product id, so a change replaces rather than accumulates. */
    public String id() {
        return String.valueOf(productId);
    }
}
