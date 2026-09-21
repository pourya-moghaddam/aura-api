package com.aura.catalog.inventory;

import com.aura.catalog.inventory.dto.SetStockRequest;
import com.aura.catalog.inventory.dto.StockLevelResponse;
import com.aura.common.security.CurrentUser;
import com.aura.common.security.SellerOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Stock as a seller manages it. Checkout's view lives on {@code /api/internal}. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/control/catalog/products/{productId}")
@SellerOnly
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/stock")
    public ResponseEntity<List<StockLevelResponse>> list(@PathVariable long productId) {
        return ResponseEntity.ok(inventoryService.listFor(CurrentUser.requiredId(), productId));
    }

    @PutMapping("/variants/{variantId}/stock")
    public ResponseEntity<StockLevelResponse> setStock(
        @PathVariable long productId,
        @PathVariable long variantId,
        @Valid @RequestBody SetStockRequest request
    ) {
        return ResponseEntity.ok(inventoryService.setOnHand(
            CurrentUser.requiredId(), productId, variantId, request.quantityOnHand()));
    }
}
