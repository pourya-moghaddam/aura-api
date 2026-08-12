package com.aura.catalog.product;

import com.aura.catalog.color.Color;
import com.aura.catalog.color.ColorService;
import com.aura.catalog.product.dto.VariantRequest;
import com.aura.catalog.product.dto.VariantResponse;
import com.aura.catalog.size.Size;
import com.aura.catalog.size.SizeService;
import com.aura.catalog.size.dto.SizeResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductVariantServiceTest {

    private static final long SELLER = 7L;
    private static final long PRODUCT_ID = 100L;
    private static final long CATEGORY_ID = 5L;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private com.aura.catalog.inventory.InventoryRepository inventoryRepository;

    @Mock
    private ProductService productService;

    @Mock
    private ColorService colorService;

    @Mock
    private SizeService sizeService;

    private ProductVariantService service;

    @BeforeEach
    void setUp() {
        service = new ProductVariantService(
            productVariantRepository, inventoryRepository, productService, colorService, sizeService);
    }

    private Product product(ProductStatus status) {
        Product product = Product.draft(SELLER, CATEGORY_ID, "Shirt", "shirt", null);
        product.setId(PRODUCT_ID);
        product.setStatus(status);
        return product;
    }

    private void ownsProduct(ProductStatus status) {
        when(productService.requireOwned(SELLER, PRODUCT_ID)).thenReturn(product(status));
    }

    private ProductVariant variant(long id, Long colorId, Long sizeId) {
        ProductVariant variant = ProductVariant.of(PRODUCT_ID, colorId, sizeId, "SKU" + id, 100_000L, null);
        variant.setId(id);
        return variant;
    }

    private VariantRequest request(Long colorId, Long sizeId, String sku) {
        return new VariantRequest(colorId, sizeId, sku, 250_000L, null, true);
    }

    private void sizeOfferedForCategory(long sizeId) {
        when(sizeService.require(sizeId)).thenReturn(new Size());
        when(sizeService.listForCategory(CATEGORY_ID))
            .thenReturn(List.of(new SizeResponse(sizeId, "L", null, 0, true)));
    }

    /** Stands in for the database assigning an id, which the service needs to create a stock row. */
    private void echoSave() {
        when(productVariantRepository.save(any())).thenAnswer(i -> {
            ProductVariant saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(42L);
            }
            return saved;
        });
    }

    @Test
    @DisplayName("a variant is added and the product's derived price and stock are refreshed")
    void variantAddedAndDerivedFieldsRefreshed() {
        // Skipping the refresh leaves a product listed at the wrong price on category pages, and
        // nothing about that fails loudly.
        ownsProduct(ProductStatus.DRAFT);
        when(colorService.require(1L)).thenReturn(new Color());
        sizeOfferedForCategory(2L);
        when(productVariantRepository.findByCombination(PRODUCT_ID, 1L, 2L)).thenReturn(Optional.empty());
        echoSave();

        VariantResponse response = service.add(SELLER, PRODUCT_ID, request(1L, 2L, "MY-SKU"));

        assertThat(response.price()).isEqualTo(250_000L);
        verify(productService).refreshDerivedFields(PRODUCT_ID);
    }

    @Test
    @DisplayName("a duplicate colour and size combination is refused with a message, not a 500")
    void duplicateCombinationRefused() {
        ownsProduct(ProductStatus.DRAFT);
        when(colorService.require(1L)).thenReturn(new Color());
        sizeOfferedForCategory(2L);
        when(productVariantRepository.findByCombination(PRODUCT_ID, 1L, 2L))
            .thenReturn(Optional.of(variant(1L, 1L, 2L)));

        assertThatThrownBy(() -> service.add(SELLER, PRODUCT_ID, request(1L, 2L, null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("colour and size");

        verify(productVariantRepository, never()).save(any());
    }

    @Test
    @DisplayName("two variants with no colour and no size collide, because NULLs are not distinct here")
    void twoNullNullVariantsCollide() {
        // The whole reason the constraint is NULLS NOT DISTINCT: under the default rule a product
        // with no axes could carry unlimited identical variants.
        ownsProduct(ProductStatus.DRAFT);
        when(productVariantRepository.findByCombination(PRODUCT_ID, null, null))
            .thenReturn(Optional.of(variant(1L, null, null)));

        assertThatThrownBy(() -> service.add(SELLER, PRODUCT_ID, request(null, null, null)))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a product with neither colour nor size is allowed one variant")
    void productWithNoAxesAllowed() {
        ownsProduct(ProductStatus.DRAFT);
        when(productVariantRepository.findByCombination(PRODUCT_ID, null, null)).thenReturn(Optional.empty());
        echoSave();

        assertThat(service.add(SELLER, PRODUCT_ID, request(null, null, null))).isNotNull();
    }

    @Test
    @DisplayName("a size that the product's category does not offer is refused")
    void sizeOutsideCategoryRefused() {
        // Sizes are scoped to a category and inherited down the tree; a foreign key alone would
        // happily put a shoe size on a T-shirt.
        ownsProduct(ProductStatus.DRAFT);
        when(sizeService.require(42L)).thenReturn(new Size());
        when(sizeService.listForCategory(CATEGORY_ID))
            .thenReturn(List.of(new SizeResponse(2L, "L", null, 0, true)));

        assertThatThrownBy(() -> service.add(SELLER, PRODUCT_ID, request(null, 42L, null)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("not available for this product's category");
    }

    @Test
    @DisplayName("an unknown colour is a 404")
    void unknownColourRefused() {
        ownsProduct(ProductStatus.DRAFT);
        when(colorService.require(99L)).thenThrow(ResourceNotFoundException.of("Color", 99L));

        assertThatThrownBy(() -> service.add(SELLER, PRODUCT_ID, request(99L, null, null)))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a SKU is generated when the seller supplies none")
    void skuGenerated() {
        ownsProduct(ProductStatus.DRAFT);
        when(productVariantRepository.findByCombination(PRODUCT_ID, null, null)).thenReturn(Optional.empty());
        when(productVariantRepository.existsBySku(anyString())).thenReturn(false);
        echoSave();

        assertThat(service.add(SELLER, PRODUCT_ID, request(null, null, null)).sku())
            .isEqualTo("SHIRT-X-X");
    }

    @Test
    @DisplayName("a generated SKU that collides gets a suffix rather than failing")
    void generatedSkuAvoidsCollision() {
        ownsProduct(ProductStatus.DRAFT);
        when(productVariantRepository.findByCombination(PRODUCT_ID, null, null)).thenReturn(Optional.empty());
        when(productVariantRepository.existsBySku("SHIRT-X-X")).thenReturn(true);
        when(productVariantRepository.existsBySku("SHIRT-X-X-1")).thenReturn(false);
        echoSave();

        assertThat(service.add(SELLER, PRODUCT_ID, request(null, null, null)).sku())
            .isEqualTo("SHIRT-X-X-1");
    }

    @Test
    @DisplayName("a supplied SKU already in use is refused")
    void duplicateSuppliedSkuRefused() {
        ownsProduct(ProductStatus.DRAFT);
        when(productVariantRepository.findByCombination(PRODUCT_ID, null, null)).thenReturn(Optional.empty());
        when(productVariantRepository.findBySku("TAKEN")).thenReturn(Optional.of(variant(9L, null, null)));

        assertThatThrownBy(() -> service.add(SELLER, PRODUCT_ID, request(null, null, "TAKEN")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already in use");
    }

    @Test
    @DisplayName("a published product may not lose its last active variant")
    void publishedProductKeepsAVariant() {
        // Without one it has no price and nothing to add to a cart, while still being listed.
        ownsProduct(ProductStatus.ACTIVE);
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(variant(1L, null, null)));
        when(productVariantRepository.countByProductIdAndIsActiveTrue(PRODUCT_ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(SELLER, PRODUCT_ID, 1L))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("at least one active variant");

        verify(productVariantRepository, never()).delete(any());
    }

    @Test
    @DisplayName("a draft may lose its last variant")
    void draftMayLoseLastVariant() {
        ownsProduct(ProductStatus.DRAFT);
        ProductVariant only = variant(1L, null, null);
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(only));

        service.delete(SELLER, PRODUCT_ID, 1L);

        verify(productVariantRepository).delete(only);
        verify(productService).refreshDerivedFields(PRODUCT_ID);
    }

    @Test
    @DisplayName("a variant belonging to another product is a 404")
    void foreignVariantIsNotFound() {
        ownsProduct(ProductStatus.DRAFT);
        ProductVariant otherProducts = ProductVariant.of(999L, null, null, "SKU", 1L, null);
        otherProducts.setId(1L);
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(otherProducts));

        assertThatThrownBy(() -> service.delete(SELLER, PRODUCT_ID, 1L))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updating a variant does not clash with itself on its own combination")
    void updateDoesNotClashWithItself() {
        ownsProduct(ProductStatus.DRAFT);
        ProductVariant existing = variant(1L, 1L, 2L);
        when(productVariantRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(colorService.require(1L)).thenReturn(new Color());
        sizeOfferedForCategory(2L);
        when(productVariantRepository.findByCombination(PRODUCT_ID, 1L, 2L)).thenReturn(Optional.of(existing));
        echoSave();

        assertThat(service.update(SELLER, PRODUCT_ID, 1L, request(1L, 2L, null)).price())
            .isEqualTo(250_000L);
        verify(productService).refreshDerivedFields(PRODUCT_ID);
    }

    @Test
    @DisplayName("ownership is checked before anything else")
    void ownershipCheckedFirst() {
        when(productService.requireOwned(SELLER, PRODUCT_ID))
            .thenThrow(ResourceNotFoundException.of("Product", PRODUCT_ID));

        assertThatThrownBy(() -> service.add(SELLER, PRODUCT_ID, request(1L, 2L, null)))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(colorService, sizeService);
    }
}
