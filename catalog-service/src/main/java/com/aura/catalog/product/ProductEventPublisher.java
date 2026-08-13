package com.aura.catalog.product;

import com.aura.catalog.category.Category;
import com.aura.catalog.category.CategoryRepository;
import com.aura.catalog.color.ColorRepository;
import com.aura.catalog.outbox.OutboxWriter;
import com.aura.catalog.size.SizeRepository;
import com.aura.common.events.ProductChangedEvent;
import com.aura.common.events.Topics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Builds the {@code ProductChanged} document and hands it to the outbox.
 *
 * <p>Assembled here rather than in search-service because catalog is the only thing that can:
 * resolving a category path, colour and size names, and the primary image means five joins across
 * tables search-service has no access to. Sending an id instead would make every indexing pass a
 * call back into this service.
 */
@Component
@RequiredArgsConstructor
public class ProductEventPublisher {

    private final OutboxWriter outboxWriter;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductMediaRepository productMediaRepository;
    private final ColorRepository colorRepository;
    private final SizeRepository sizeRepository;

    /**
     * Records that a product changed.
     *
     * <p>{@code MANDATORY}: this only means anything inside the transaction that made the change.
     * Called on its own it would write an event describing a state that might never commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void productChanged(Product product) {
        publish(product, !product.getStatus().isVisibleToShoppers());
    }

    /**
     * Records that a product should leave the index.
     *
     * <p>A flagged event rather than an absent one. Publishing nothing on delete would leave the
     * document in the index forever; publishing a separate event type would put it on a different
     * ordering path from the changes that preceded it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void productDeleted(Product product) {
        publish(product, true);
    }

    private void publish(Product product, boolean deleted) {
        List<ProductVariant> variants =
            productVariantRepository.findByProductIdOrderByIdAsc(product.getId());
        List<Long> path = categoryPathOf(product.getCategoryId());

        ProductChangedEvent event = new ProductChangedEvent(
            UUID.randomUUID(),
            Instant.now(),
            product.getId(),
            product.getSellerId(),
            product.getCategoryId(),
            path,
            categoryNamesOf(path),
            product.getName(),
            product.getSlug(),
            product.getDescription(),
            product.getStatus().name(),
            product.getMinPrice(),
            product.getMaxPrice(),
            product.getTotalStock(),
            product.getAttributes(),
            colourNames(variants),
            sizeNames(variants),
            primaryMediaId(product.getId()),
            product.getCreatedAt() == null ? null : product.getCreatedAt().toInstant(),
            // updated_at as epoch millis, used as an external version so a redelivered older
            // document cannot overwrite a newer one.
            product.getUpdatedAt() == null ? 0L : product.getUpdatedAt().toInstant().toEpochMilli(),
            deleted);

        outboxWriter.write(Topics.PRODUCT_CHANGED, event.partitionKey(), event);
    }

    /**
     * Ancestor ids, root first, taken from the ltree path rather than by walking parents. Lets
     * search answer "everything under Clothing" with a term filter instead of asking catalog for
     * the subtree on every query.
     */
    private List<Long> categoryPathOf(Long categoryId) {
        return categoryRepository.findById(categoryId)
            .map(Category::getPath)
            .map(path -> Arrays.stream(path.split("\\.")).map(Long::valueOf).toList())
            .orElse(List.of());
    }

    /**
     * The same ancestors as names, in the same order.
     *
     * <p>Resolved from the ids already in hand rather than by a second traversal, and reordered to
     * match the path — {@code findAllById} makes no promise about order, and a category list that
     * reads "Shirts, Clothing" instead of "Clothing, Shirts" would be shown to shoppers that way.
     */
    private List<String> categoryNamesOf(List<Long> path) {
        if (path.isEmpty()) {
            return List.of();
        }
        java.util.Map<Long, String> names = categoryRepository.findAllById(path).stream()
            .collect(java.util.stream.Collectors.toMap(Category::getId, Category::getName));

        return path.stream().map(names::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Names, not ids: the facet sidebar shows them, and search should not have to resolve them. */
    private List<String> colourNames(List<ProductVariant> variants) {
        List<Long> ids = variants.stream()
            .filter(ProductVariant::isActive)
            .map(ProductVariant::getColorId)
            .filter(java.util.Objects::nonNull)
            .distinct().toList();
        return ids.isEmpty() ? List.of()
            : colorRepository.findAllById(ids).stream().map(c -> c.getName()).toList();
    }

    private List<String> sizeNames(List<ProductVariant> variants) {
        List<Long> ids = variants.stream()
            .filter(ProductVariant::isActive)
            .map(ProductVariant::getSizeId)
            .filter(java.util.Objects::nonNull)
            .distinct().toList();
        return ids.isEmpty() ? List.of()
            : sizeRepository.findAllById(ids).stream().map(s -> s.getName()).toList();
    }

    private UUID primaryMediaId(Long productId) {
        return productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(productId).stream()
            .filter(ProductMedia::isPrimary)
            .map(ProductMedia::getMediaId)
            .findFirst()
            .orElse(null);
    }
}
