package com.aura.catalog.product;

import com.aura.catalog.media.MediaGateway;
import com.aura.catalog.product.dto.ProductMediaRequest;
import com.aura.catalog.product.dto.ProductMediaResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ConflictException;
import com.aura.common.web.error.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * The media gate. Attaching a file asks media-service whether it is servable <em>and</em> whose it
 * is — catalog has no record of who uploaded what, so without that question a seller could put a
 * competitor's photograph, or an unscanned upload, on their own product by supplying its id.
 */
@ExtendWith(MockitoExtension.class)
class ProductMediaServiceTest {

    private static final long SELLER = 7L;
    private static final long PRODUCT_ID = 100L;

    @Mock
    private ProductMediaRepository productMediaRepository;

    @Mock
    private ProductService productService;

    @Mock
    private MediaGateway mediaGateway;

    private ProductMediaService service;
    private UUID mediaId;

    @BeforeEach
    void setUp() {
        service = new ProductMediaService(productMediaRepository, productService, mediaGateway);
        mediaId = UUID.randomUUID();
    }

    private Product product(ProductStatus status) {
        Product product = Product.draft(SELLER, 5L, "Shirt", "shirt", null);
        product.setId(PRODUCT_ID);
        product.setStatus(status);
        return product;
    }

    private ProductMedia media(long id, boolean primary) {
        ProductMedia media = ProductMedia.of(
            PRODUCT_ID, UUID.randomUUID(), ProductMedia.MediaKind.IMAGE, 0, primary);
        media.setId(id);
        return media;
    }

    private ProductMediaRequest request(boolean primary) {
        return new ProductMediaRequest(mediaId, ProductMedia.MediaKind.IMAGE, 0, primary);
    }

    private void ownsProduct(ProductStatus status) {
        when(productService.requireOwned(SELLER, PRODUCT_ID)).thenReturn(product(status));
    }

    @Test
    @DisplayName("a servable file the seller owns is attached")
    void usableMediaAttached() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.empty());
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);
        when(productMediaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProductMediaResponse response = service.attach(SELLER, PRODUCT_ID, request(false));

        assertThat(response.mediaId()).isEqualTo(mediaId);
    }

    @Test
    @DisplayName("attaching publishes the file, or the storefront renders a broken image")
    void attachPublishesForStorefront() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.empty());
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);
        when(productMediaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.attach(SELLER, PRODUCT_ID, request(false));

        // Shoppers carry no token, so an owner-scoped file cannot render on a product page.
        // Without this call the product looks correct in the panel and broken on the site.
        verify(mediaGateway).publish(mediaId);
    }

    @Test
    @DisplayName("an unusable file is never published")
    void unusableMediaNotPublished() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.empty());
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(false);

        assertThatThrownBy(() -> service.attach(SELLER, PRODUCT_ID, request(false)))
            .isInstanceOf(BusinessRuleException.class);

        // Ordering matters: publishing before the usability check would leave an unscanned upload
        // world-readable for as long as it took the attach to fail.
        verify(mediaGateway, never()).publish(any());
    }

    @Test
    @DisplayName("a file that is not servable or not the seller's is refused")
    void unusableMediaRefused() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.empty());
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(false);

        assertThatThrownBy(() -> service.attach(SELLER, PRODUCT_ID, request(false)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("not available");

        verify(productMediaRepository, never()).save(any());
    }

    @Test
    @DisplayName("ownership of the product is checked before media-service is asked anything")
    void productOwnershipCheckedFirst() {
        when(productService.requireOwned(SELLER, PRODUCT_ID))
            .thenThrow(ResourceNotFoundException.of("Product", PRODUCT_ID));

        assertThatThrownBy(() -> service.attach(SELLER, PRODUCT_ID, request(false)))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(mediaGateway);
    }

    @Test
    @DisplayName("attaching the same file twice is refused")
    void duplicateAttachmentRefused() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.of(media(1L, false)));

        assertThatThrownBy(() -> service.attach(SELLER, PRODUCT_ID, request(false)))
            .isInstanceOf(ConflictException.class);

        verifyNoInteractions(mediaGateway);
    }

    @Test
    @DisplayName("the first image becomes primary without being asked to")
    void firstImageIsPrimary() {
        // Something has to be the listing image, and a seller who never ticks the box would
        // otherwise get a blank tile in every listing.
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.empty());
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);
        when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(0L);
        when(productMediaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.attach(SELLER, PRODUCT_ID, request(false)).isPrimary()).isTrue();
    }

    @Test
    @DisplayName("the previous primary is demoted before the new one is set")
    void previousPrimaryClearedFirst() {
        // uq_product_media_one_primary is a unique index, so setting the new primary before
        // clearing the old one violates it mid-transaction.
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findByProductIdAndMediaId(PRODUCT_ID, mediaId))
            .thenReturn(Optional.empty());
        when(mediaGateway.isUsableBy(mediaId)).thenReturn(true);
        when(productMediaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.attach(SELLER, PRODUCT_ID, request(true));

        var inOrder = inOrder(productMediaRepository);
        inOrder.verify(productMediaRepository).clearPrimary(PRODUCT_ID);
        inOrder.verify(productMediaRepository).save(any());
    }

    @Test
    @DisplayName("promoting an image demotes the previous primary")
    void makePrimaryClearsOthers() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findById(2L)).thenReturn(Optional.of(media(2L, false)));
        when(productMediaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        assertThat(service.makePrimary(SELLER, PRODUCT_ID, 2L).isPrimary()).isTrue();
        verify(productMediaRepository).clearPrimary(PRODUCT_ID);
    }

    @Test
    @DisplayName("media belonging to another product is a 404")
    void foreignMediaIsNotFound() {
        ownsProduct(ProductStatus.DRAFT);
        ProductMedia otherProducts = ProductMedia.of(
            999L, UUID.randomUUID(), ProductMedia.MediaKind.IMAGE, 0, false);
        otherProducts.setId(2L);
        when(productMediaRepository.findById(2L)).thenReturn(Optional.of(otherProducts));

        assertThatThrownBy(() -> service.makePrimary(SELLER, PRODUCT_ID, 2L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a published product may not lose its last image")
    void publishedProductKeepsAnImage() {
        ownsProduct(ProductStatus.ACTIVE);
        when(productMediaRepository.findById(2L)).thenReturn(Optional.of(media(2L, true)));
        when(productMediaRepository.countByProductId(PRODUCT_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.detach(SELLER, PRODUCT_ID, 2L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("at least one image");

        verify(productMediaRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a draft may be stripped of all its images")
    void draftMayLoseEveryImage() {
        ownsProduct(ProductStatus.DRAFT);
        ProductMedia only = media(2L, true);
        when(productMediaRepository.findById(2L)).thenReturn(Optional.of(only));
        when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of());

        service.detach(SELLER, PRODUCT_ID, 2L);

        verify(productMediaRepository).delete(only);
    }

    @Test
    @DisplayName("removing the primary promotes the next image, so a listing image always exists")
    void removingPrimaryPromotesNext() {
        ownsProduct(ProductStatus.DRAFT);
        ProductMedia primary = media(2L, true);
        ProductMedia next = media(3L, false);
        when(productMediaRepository.findById(2L)).thenReturn(Optional.of(primary));
        when(productMediaRepository.findByProductIdOrderBySortOrderAscIdAsc(PRODUCT_ID))
            .thenReturn(List.of(next));
        when(productMediaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.detach(SELLER, PRODUCT_ID, 2L);

        assertThat(next.isPrimary()).isTrue();
        verify(productMediaRepository).save(next);
    }

    @Test
    @DisplayName("removing a non-primary image promotes nothing")
    void removingNonPrimaryPromotesNothing() {
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findById(3L)).thenReturn(Optional.of(media(3L, false)));

        service.detach(SELLER, PRODUCT_ID, 3L);

        verify(productMediaRepository, never()).save(any());
    }

    @Test
    @DisplayName("detaching never deletes the underlying file")
    void detachLeavesTheFileAlone() {
        // media-service owns it, the seller may be using it on another product, and removing it
        // from one listing is not consent to destroy it.
        ownsProduct(ProductStatus.DRAFT);
        when(productMediaRepository.findById(3L)).thenReturn(Optional.of(media(3L, false)));

        service.detach(SELLER, PRODUCT_ID, 3L);

        verifyNoInteractions(mediaGateway);
    }
}
