package com.aura.catalog.inventory.dto;

import com.aura.catalog.inventory.Inventory;

/**
 * @param available what a shopper may still buy: on hand minus what unpaid orders are holding.
 *                  The figure the storefront should show, since the other two are internal
 *                  bookkeeping.
 */
public record StockLevelResponse(
    Long variantId,
    int quantityOnHand,
    int quantityReserved,
    int available
) {

    public static StockLevelResponse from(Inventory inventory) {
        return new StockLevelResponse(inventory.getVariantId(), inventory.getQuantityOnHand(),
            inventory.getQuantityReserved(), inventory.available());
    }
}
