package com.aura.catalog.product;

import com.aura.catalog.product.dto.VariantSnapshotResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * What order-service reads to price a cart and build an order.
 *
 * <p>Under {@code /api/internal} with the rest of the service-to-service surface: the gateway does
 * not route the prefix and the API-key filter guards it. It carries no user identity because guest
 * checkout means there may not be one — and because "what does this variant cost" is not a
 * question whose answer depends on who is asking.
 */
@RestController
@RequiredArgsConstructor
public class InternalVariantController {

    private final VariantSnapshotService variantSnapshotService;

    @GetMapping("/api/internal/catalog/variants")
    public ResponseEntity<List<VariantSnapshotResponse>> snapshots(@RequestParam List<Long> ids) {
        return ResponseEntity.ok(variantSnapshotService.snapshotsFor(ids));
    }
}
