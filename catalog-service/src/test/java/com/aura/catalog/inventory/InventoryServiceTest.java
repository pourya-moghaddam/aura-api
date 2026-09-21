package com.aura.catalog.inventory;

import com.aura.catalog.inventory.dto.StockLevelResponse;
import com.aura.catalog.product.Product;
import com.aura.catalog.product.ProductService;
import com.aura.catalog.product.ProductStatus;
import com.aura.catalog.product.ProductVariant;
import com.aura.catalog.product.ProductVariantRepository;
import com.aura.common.web.error.BusinessRuleException;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    private static final long SELLER = 7L;
    private static final long PRODUCT_ID = 100L;
    private static final long VARIANT_ID = 1L;

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ProductVariantRepository productVariantRepository;

    @Mock
    private ProductService productService;

    private InventoryService service;

    @BeforeEach
    void setUp() {
        service = new InventoryService(inventoryRepository, productVariantRepository, productService);
    }

    private void ownsProduct() {
        Product product = Product.draft(SELLER, 5L, "P", "p", null);
        product.setId(PRODUCT_ID);
        product.setStatus(ProductStatus.DRAFT);
        when(productService.requireOwned(SELLER, PRODUCT_ID)).thenReturn(product);
    }

    private void variantBelongs() {
        ProductVariant variant = ProductVariant.of(PRODUCT_ID, null, null, "SKU", 1000L, null);
        variant.setId(VARIANT_ID);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(variant));
    }

    private Inventory inventory(int onHand, int reserved) {
        Inventory inventory = Inventory.forVariant(VARIANT_ID, onHand);
        inventory.setQuantityReserved(reserved);
        return inventory;
    }

    private void echoSave() {
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("setting stock is absolute, not a delta")
    void setsAnAbsoluteFigure() {
        // A delta applied twice by a resubmitted form is wrong in a way nobody notices until the
        // numbers have drifted well away from the shelf.
        ownsProduct();
        variantBelongs();
        when(inventoryRepository.lockOne(VARIANT_ID)).thenReturn(Optional.of(inventory(5, 0)));
        echoSave();

        StockLevelResponse response = service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 12);

        assertThat(response.quantityOnHand()).isEqualTo(12);
        assertThat(response.available()).isEqualTo(12);
    }

    @Test
    @DisplayName("stock cannot be cut below what orders are already holding")
    void refusesToCutBelowReserved() {
        // Those shoppers are mid-checkout with a promise against this stock. The database's
        // ck_inventory_not_oversold would refuse it too, but as a 500 rather than an explanation.
        ownsProduct();
        variantBelongs();
        when(inventoryRepository.lockOne(VARIANT_ID)).thenReturn(Optional.of(inventory(10, 4)));

        assertThatThrownBy(() -> service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 2))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("held by orders");

        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("setting stock exactly to what is reserved is allowed")
    void exactlyReservedIsAllowed() {
        ownsProduct();
        variantBelongs();
        when(inventoryRepository.lockOne(VARIANT_ID)).thenReturn(Optional.of(inventory(10, 4)));
        echoSave();

        assertThat(service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 4).available()).isZero();
    }

    @Test
    @DisplayName("a variant with no stock row yet gets one")
    void createsTheRowWhenAbsent() {
        ownsProduct();
        variantBelongs();
        when(inventoryRepository.lockOne(VARIANT_ID)).thenReturn(Optional.empty());
        echoSave();

        assertThat(service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 3).quantityOnHand()).isEqualTo(3);
    }

    @Test
    @DisplayName("the product's denormalised total is refreshed after a stock change")
    void refreshesDerivedFields() {
        // total_stock feeds the storefront's in-stock filter; leaving it stale shows sold-out
        // products as available.
        ownsProduct();
        variantBelongs();
        when(inventoryRepository.lockOne(VARIANT_ID)).thenReturn(Optional.of(inventory(0, 0)));
        echoSave();

        service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 7);

        verify(productService).refreshDerivedFields(PRODUCT_ID);
    }

    @Test
    @DisplayName("ownership is checked before any stock is touched")
    void ownershipCheckedFirst() {
        when(productService.requireOwned(SELLER, PRODUCT_ID))
            .thenThrow(ResourceNotFoundException.of("Product", PRODUCT_ID));

        assertThatThrownBy(() -> service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 5))
            .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(inventoryRepository);
    }

    @Test
    @DisplayName("a variant belonging to another product is a 404")
    void foreignVariantRefused() {
        ownsProduct();
        ProductVariant otherProducts = ProductVariant.of(999L, null, null, "SKU", 1000L, null);
        otherProducts.setId(VARIANT_ID);
        when(productVariantRepository.findById(VARIANT_ID)).thenReturn(Optional.of(otherProducts));

        assertThatThrownBy(() -> service.setOnHand(SELLER, PRODUCT_ID, VARIANT_ID, 5))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("listing stock is scoped to the seller's own product")
    void listScopedToOwner() {
        ownsProduct();
        when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID))
            .thenReturn(List.of(variantWithId()));
        when(inventoryRepository.findByVariantIdIn(List.of(VARIANT_ID)))
            .thenReturn(List.of(inventory(5, 1)));

        List<StockLevelResponse> levels = service.listFor(SELLER, PRODUCT_ID);

        assertThat(levels).hasSize(1);
        assertThat(levels.getFirst().available()).isEqualTo(4);
    }

    @Test
    @DisplayName("a product with no variants lists nothing without querying inventory")
    void noVariantsShortCircuits() {
        ownsProduct();
        when(productVariantRepository.findByProductIdOrderByIdAsc(PRODUCT_ID)).thenReturn(List.of());

        assertThat(service.listFor(SELLER, PRODUCT_ID)).isEmpty();
        verify(inventoryRepository, never()).findByVariantIdIn(any());
    }

    @Test
    @DisplayName("an unstocked variant reads as zero available, not as an error")
    void unstockedVariantIsZero() {
        when(inventoryRepository.findById(VARIANT_ID)).thenReturn(Optional.empty());

        assertThat(service.availableFor(VARIANT_ID)).isZero();
    }

    @Test
    @DisplayName("available subtracts what is held")
    void availableSubtractsReserved() {
        when(inventoryRepository.findById(VARIANT_ID)).thenReturn(Optional.of(inventory(10, 3)));

        assertThat(service.availableFor(VARIANT_ID)).isEqualTo(7);
    }

    private ProductVariant variantWithId() {
        ProductVariant variant = ProductVariant.of(PRODUCT_ID, null, null, "SKU", 1000L, null);
        variant.setId(VARIANT_ID);
        return variant;
    }
}
