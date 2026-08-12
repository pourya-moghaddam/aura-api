package com.aura.catalog.product;

import com.aura.catalog.category.Category;
import com.aura.catalog.category.CategoryRepository;
import com.aura.catalog.color.Color;
import com.aura.catalog.color.ColorRepository;
import com.aura.catalog.outbox.OutboxWriter;
import com.aura.catalog.size.Size;
import com.aura.catalog.size.SizeRepository;
import com.aura.common.events.ProductChangedEvent;
import com.aura.common.events.Topics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The document search-service indexes.
 *
 * <p>Everything asserted here is something search cannot recover on its own: it has no access to
 * the category tree, the colour and size tables, or the media links, so anything omitted from this
 * payload is simply absent from the index — a facet with no values, a product that cannot be found
 * by browsing its parent category, a listing with no thumbnail.
 */
@ExtendWith(MockitoExtension.class)
class ProductEventPublisherTest {

    private static final long PRODUCT_ID = 100L;
    private static final long CATEGORY_ID = 5L;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductMediaRepository productMediaRepository;

    @Mock
    private ColorRepository colorRepository;

    @Mock
    private SizeRepository sizeRepository;

    private ProductEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new ProductEventPublisher(outboxWriter, categoryRepository,
            productVariantRepository, productMediaRepository, colorRepository, sizeRepository);
    }

    private Product product(ProductStatus status) {
        Product product = Product.draft(7L, CATEGORY_ID, "Oxford Shirt", "oxford-shirt", "A shirt");
        product.setId(PRODUCT_ID);
        product.setStatus(status);
        product.setMinPrice(250_000L);
        product.setMaxPrice(300_000L);
        product.setTotalStock(4);
        product.setAttributes(Map.of("material", List.of("cotton")));
        return product;
    }

    private ProductVariant variant(long id, Long colorId, Long sizeId, boolean active) {
        ProductVariant variant = ProductVariant.of(PRODUCT_ID, colorId, sizeId, "SKU" + id, 1000L, null);
        variant.setId(id);
        variant.setActive(active);
        return variant;
    }

    private void categoryPath(String path) {
        Category category = new Category();
        category.setId(CATEGORY_ID);
        category.setPath(path);
        lenient().when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(category));
    }

    private void noVariantsOrMedia() {
        lenient().when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID))
            .thenReturn(List.of());
        lenient().when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of());
    }

    private ProductChangedEvent captureEvent() {
        ArgumentCaptor<ProductChangedEvent> captor = ArgumentCaptor.forClass(ProductChangedEvent.class);
        verify(outboxWriter).write(eq(Topics.PRODUCT_CHANGED), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the whole document travels, so search never has to call back")
    void carriesTheWholeDocument() {
        categoryPath("1.5");
        noVariantsOrMedia();

        publisher.productChanged(product(ProductStatus.ACTIVE));

        ProductChangedEvent event = captureEvent();
        assertThat(event.productId()).isEqualTo(PRODUCT_ID);
        assertThat(event.name()).isEqualTo("Oxford Shirt");
        assertThat(event.slug()).isEqualTo("oxford-shirt");
        assertThat(event.minPrice()).isEqualTo(250_000L);
        assertThat(event.maxPrice()).isEqualTo(300_000L);
        assertThat(event.totalStock()).isEqualTo(4);
        assertThat(event.attributes()).containsEntry("material", List.of("cotton"));
        assertThat(event.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the category path is the ancestor chain, so browsing a parent finds it")
    void categoryPathIsTheAncestorChain() {
        // Without this, search cannot answer "everything under Clothing" without asking catalog
        // for the subtree on every query.
        categoryPath("1.3.5");
        noVariantsOrMedia();

        publisher.productChanged(product(ProductStatus.ACTIVE));

        assertThat(captureEvent().categoryPath()).containsExactly(1L, 3L, 5L);
    }

    @Test
    @DisplayName("colour and size names travel, not ids — the facet sidebar shows names")
    void axisNamesAreResolved() {
        categoryPath("5");
        when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID))
            .thenReturn(List.of(variant(1L, 10L, 20L, true)));
        when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of());
        when(colorRepository.findAllById(List.of(10L))).thenReturn(List.of(Color.of("Navy", "#000080", 0)));
        when(sizeRepository.findAllById(List.of(20L))).thenReturn(List.of(Size.of("L", null, 0)));

        publisher.productChanged(product(ProductStatus.ACTIVE));

        ProductChangedEvent event = captureEvent();
        assertThat(event.colorNames()).containsExactly("Navy");
        assertThat(event.sizeNames()).containsExactly("L");
    }

    @Test
    @DisplayName("an inactive variant's colour is not offered as a filter")
    void inactiveVariantsExcludedFromFacets() {
        // Filtering by it would return a product the shopper cannot actually buy in that colour.
        categoryPath("5");
        when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID))
            .thenReturn(List.of(variant(1L, 10L, null, false)));
        when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of());

        publisher.productChanged(product(ProductStatus.ACTIVE));

        assertThat(captureEvent().colorNames()).isEmpty();
        verifyNoInteractions(colorRepository);
    }

    @Test
    @DisplayName("a product with no colour or size axis produces empty facets, not nulls")
    void noAxesGivesEmptyLists() {
        categoryPath("5");
        when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID))
            .thenReturn(List.of(variant(1L, null, null, true)));
        when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of());

        ProductChangedEvent event;
        publisher.productChanged(product(ProductStatus.ACTIVE));
        event = captureEvent();

        assertThat(event.colorNames()).isEmpty();
        assertThat(event.sizeNames()).isEmpty();
    }

    @Test
    @DisplayName("the primary image travels, so listings have a thumbnail")
    void primaryMediaIsCarried() {
        UUID primary = UUID.randomUUID();
        categoryPath("5");
        when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID)).thenReturn(List.of());
        when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of(
                ProductMedia.of(PRODUCT_ID, UUID.randomUUID(), ProductMedia.MediaKind.IMAGE, 0, false),
                ProductMedia.of(PRODUCT_ID, primary, ProductMedia.MediaKind.IMAGE, 1, true)));

        publisher.productChanged(product(ProductStatus.ACTIVE));

        assertThat(captureEvent().primaryMediaId()).isEqualTo(primary);
    }

    @Test
    @DisplayName("the version is updated_at in millis, so a redelivery cannot overwrite a newer document")
    void versionComesFromUpdatedAt() {
        // Kafka only orders within a partition and redelivers on retry. Indexed with
        // version_type: external, this is what stops an old document landing on top of a new one.
        categoryPath("5");
        noVariantsOrMedia();
        Product product = product(ProductStatus.ACTIVE);
        OffsetDateTime updatedAt = OffsetDateTime.now();
        product.setUpdatedAt(updatedAt);

        publisher.productChanged(product);

        assertThat(captureEvent().version()).isEqualTo(updatedAt.toInstant().toEpochMilli());
    }

    @Test
    @DisplayName("events are keyed by product, so one product's events stay in order")
    void partitionKeyIsTheProductId() {
        categoryPath("5");
        noVariantsOrMedia();

        publisher.productChanged(product(ProductStatus.ACTIVE));

        verify(outboxWriter).write(eq(Topics.PRODUCT_CHANGED), eq("100"), any());
    }

    @Test
    @DisplayName("a draft is flagged deleted, so it never appears in search")
    void draftIsFlaggedDeleted() {
        categoryPath("5");
        noVariantsOrMedia();

        publisher.productChanged(product(ProductStatus.DRAFT));

        assertThat(captureEvent().deleted()).isTrue();
    }

    @Test
    @DisplayName("an active product is not flagged deleted")
    void activeIsNotDeleted() {
        categoryPath("5");
        noVariantsOrMedia();

        publisher.productChanged(product(ProductStatus.ACTIVE));

        assertThat(captureEvent().deleted()).isFalse();
    }

    @Test
    @DisplayName("an explicit deletion is flagged even for an active product")
    void explicitDeletionIsFlagged() {
        categoryPath("5");
        noVariantsOrMedia();

        publisher.productDeleted(product(ProductStatus.ACTIVE));

        assertThat(captureEvent().deleted()).isTrue();
    }

    @Test
    @DisplayName("a missing category yields an empty path rather than failing the write")
    void missingCategoryDoesNotBreakThePublish() {
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.empty());
        noVariantsOrMedia();

        publisher.productChanged(product(ProductStatus.ACTIVE));

        assertThat(captureEvent().categoryPath()).isEmpty();
    }
}
