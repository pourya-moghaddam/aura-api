package com.aura.search.index;

import com.aura.common.events.ProductChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning catalog's event into the document this service stores.
 *
 * <p>The two are kept separate deliberately — the event is a contract, the document is storage —
 * so this is where the one derived field lives and where the translation can go wrong quietly.
 */
class ProductDocumentTest {

    static ProductChangedEvent event(Integer totalStock, UUID mediaId) {
        return new ProductChangedEvent(
            UUID.randomUUID(), Instant.parse("2026-08-13T10:00:00Z"),
            42L, 9L, 3L, List.of(1L, 3L), List.of("پوشاک", "پیراهن"),
            "پیراهن مردانه", "mens-shirt", "توضیحات",
            "ACTIVE", 500_000L, 900_000L, totalStock,
            Map.of("material", List.of("Cotton")),
            List.of("Navy"), List.of("L"),
            mediaId, 1_700_000_000_000L, false);
    }

    @Test
    @DisplayName("every field catalog sends arrives in the document")
    void carriesTheEvent() {
        ProductDocument document = ProductDocument.from(event(5, null));

        assertThat(document.productId()).isEqualTo(42L);
        assertThat(document.sellerId()).isEqualTo(9L);
        assertThat(document.categoryId()).isEqualTo(3L);
        assertThat(document.categoryPath()).containsExactly(1L, 3L);
        assertThat(document.categoryNames()).containsExactly("پوشاک", "پیراهن");
        assertThat(document.name()).isEqualTo("پیراهن مردانه");
        assertThat(document.minPrice()).isEqualTo(500_000L);
        assertThat(document.attributes()).containsEntry("material", List.of("Cotton"));
        assertThat(document.colorNames()).containsExactly("Navy");
    }

    @Test
    @DisplayName("the document id is the product id, so a change replaces rather than accumulates")
    void idIsTheProductId() {
        assertThat(ProductDocument.from(event(1, null)).id()).isEqualTo("42");
    }

    @Test
    @DisplayName("in-stock is derived here, because it is a search concern")
    void derivesInStock() {
        // Catalog has no opinion about how a shopper filters; it sends a count. "Can I buy this"
        // is this service's question to answer.
        assertThat(ProductDocument.from(event(3, null)).inStock()).isTrue();
        assertThat(ProductDocument.from(event(0, null)).inStock()).isFalse();
        assertThat(ProductDocument.from(event(null, null)).inStock()).isFalse();
    }

    @Test
    @DisplayName("a product with no image indexes without one, rather than not at all")
    void toleratesAMissingImage() {
        assertThat(ProductDocument.from(event(1, null)).primaryMediaId()).isNull();
    }

    @Test
    @DisplayName("the image id travels as a string, because that is what a keyword field holds")
    void mediaIdAsString() {
        UUID mediaId = UUID.randomUUID();

        assertThat(ProductDocument.from(event(1, mediaId)).primaryMediaId())
            .isEqualTo(mediaId.toString());
    }
}
