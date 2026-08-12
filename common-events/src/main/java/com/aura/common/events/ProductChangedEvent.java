package com.aura.common.events;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emitted by catalog-service whenever a product's searchable shape changes.
 *
 * <p>Carries the whole denormalised document rather than an id. Search-service is a separate
 * service with its own datastore; an id-only event would mean it calls back to catalog for every
 * message, which turns a catalogue-wide reindex into a stampede and makes indexing fail whenever
 * catalog is down. The trade is a larger message, which Kafka does not care about.
 *
 * @param version   the product's {@code updated_at} as epoch millis, indexed with Elasticsearch's
 *                  {@code version_type: external}. Kafka only orders within a partition, and a
 *                  redelivery can arrive after a newer write; without this an old document
 *                  silently overwrites a newer one and the index is wrong until the next edit.
 * @param deleted   true when the product should leave the index — archived or removed. A separate
 *                  flag rather than a separate event so ordering between "changed" and "deleted"
 *                  is preserved by the same partition key.
 * @param attributes field slug to chosen value slugs, the shape the storefront's facets aggregate
 *                  on.
 */
public record ProductChangedEvent(
    UUID eventId,
    Instant occurredAt,
    Long productId,
    Long sellerId,
    Long categoryId,
    /** Ancestor ids, root first. Lets search answer "everything under Clothing" without the tree. */
    List<Long> categoryPath,
    String name,
    String slug,
    String description,
    String status,
    Long minPrice,
    Long maxPrice,
    Integer totalStock,
    Map<String, List<String>> attributes,
    List<String> colorNames,
    List<String> sizeNames,
    UUID primaryMediaId,
    long version,
    boolean deleted
) implements DomainEvent {

    /** Partition key. Every event for one product goes to the same partition, so they stay ordered. */
    public String partitionKey() {
        return String.valueOf(productId);
    }
}
