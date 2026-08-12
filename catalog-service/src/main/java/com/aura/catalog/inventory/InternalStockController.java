package com.aura.catalog.inventory;

import com.aura.catalog.inventory.dto.ReservationResponse;
import com.aura.catalog.inventory.dto.ReserveStockRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The stock side of checkout, called by order-service.
 *
 * <p>Under {@code /api/internal} rather than {@code /api/catalog}: the gateway does not route this
 * prefix, so it is unreachable from outside, and the API-key filter guards it from anything else on
 * the internal network. No user identity is involved — guest checkout means there may not be one,
 * and "may this service move stock" is a different question from "who is buying".
 *
 * <p>All three operations are idempotent, because the caller retries. Reserving twice for one order
 * returns the existing hold, and committing or releasing twice is a no-op.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/catalog/stock")
public class InternalStockController {

    private final StockReservationService stockReservationService;

    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveStockRequest request) {
        return ResponseEntity.ok(stockReservationService.reserve(request));
    }

    /** Payment cleared: the units leave stock for good. */
    @PostMapping("/reservations/{orderId}/commit")
    public ResponseEntity<Void> commit(@PathVariable long orderId) {
        stockReservationService.commit(orderId);
        return ResponseEntity.noContent().build();
    }

    /** Payment failed or the order was abandoned: the units go back on the shelf. */
    @PostMapping("/reservations/{orderId}/release")
    public ResponseEntity<Void> release(@PathVariable long orderId) {
        stockReservationService.release(orderId);
        return ResponseEntity.noContent().build();
    }
}
