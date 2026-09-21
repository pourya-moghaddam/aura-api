package com.aura.catalog.inventory;

import com.aura.catalog.inventory.dto.StockLevelResponse;
import com.aura.catalog.product.ProductService;
import com.aura.catalog.product.ProductVariant;
import com.aura.catalog.product.ProductVariantRepository;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * What a seller does to stock, as opposed to what checkout does to it.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ProductVariantRepository productVariantRepository;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public List<StockLevelResponse> listFor(long userId, long productId) {
        productService.requireOwned(userId, productId);

        List<Long> variantIds = productVariantRepository.findByProductIdOrderByIdAsc(productId)
            .stream().map(ProductVariant::getId).toList();
        if (variantIds.isEmpty()) {
            return List.of();
        }

        return inventoryRepository.findByVariantIdIn(variantIds).stream()
            .map(StockLevelResponse::from).toList();
    }

    /**
     * Sets a variant's stock to an absolute figure.
     *
     * <p>Absolute rather than a delta because a seller counting a shelf knows how many there are,
     * and a delta applied twice by a resubmitted form is wrong in a way nobody notices until the
     * numbers drift.
     *
     * <p>Refused if it would drop below what unpaid orders are already holding. Those shoppers are
     * mid-checkout with a promise against this stock; the database's
     * {@code ck_inventory_not_oversold} constraint would refuse it anyway, but as a 500 rather than
     * as a sentence explaining why.
     */
    @Transactional
    public StockLevelResponse setOnHand(long userId, long productId, long variantId, int quantityOnHand) {
        productService.requireOwned(userId, productId);
        requireVariantOf(productId, variantId);

        Inventory inventory = inventoryRepository.lockOne(variantId)
            .orElseGet(() -> Inventory.forVariant(variantId, 0));

        if (quantityOnHand < inventory.getQuantityReserved()) {
            throw new BusinessRuleException("stock-below-reserved",
                "There are " + inventory.getQuantityReserved() + " units held by orders being paid "
                    + "for, so stock cannot be set below that.");
        }

        inventory.setQuantityOnHand(quantityOnHand);
        inventory.touch();
        StockLevelResponse response = StockLevelResponse.from(inventoryRepository.save(inventory));

        // The product's total_stock feeds the storefront's in-stock filter and sorting, so it has
        // to follow every change to the underlying counters.
        productService.refreshDerivedFields(productId);

        return response;
    }

    /** What a shopper may buy. Zero when the variant was never stocked. */
    @Transactional(readOnly = true)
    public int availableFor(long variantId) {
        return inventoryRepository.findById(variantId).map(Inventory::available).orElse(0);
    }

    private void requireVariantOf(long productId, long variantId) {
        productVariantRepository.findById(variantId)
            .filter(variant -> variant.getProductId() == productId)
            .orElseThrow(() -> ResourceNotFoundException.of("Variant", variantId));
    }
}
