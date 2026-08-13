package com.aura.order.delivery;

import com.aura.common.security.AdminOnly;
import com.aura.order.delivery.dto.DeliveryMethodRequest;
import com.aura.order.delivery.dto.DeliveryMethodResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class DeliveryMethodController {

    private final DeliveryMethodService deliveryMethodService;

    /**
     * The options a shopper picks from. Anonymous, because a guest reaches checkout without ever
     * signing in — requirement 12.
     */
    @GetMapping("/api/orders/delivery-methods")
    public ResponseEntity<List<DeliveryMethodResponse>> listActive() {
        return ResponseEntity.ok(deliveryMethodService.listActive());
    }

    @GetMapping("/api/control/orders/delivery-methods")
    @AdminOnly
    public ResponseEntity<List<DeliveryMethodResponse>> listAll() {
        return ResponseEntity.ok(deliveryMethodService.listAll());
    }

    @PostMapping("/api/control/orders/delivery-methods")
    @AdminOnly
    public ResponseEntity<DeliveryMethodResponse> create(
        @Valid @RequestBody DeliveryMethodRequest request
    ) {
        DeliveryMethodResponse created = deliveryMethodService.create(request);
        return ResponseEntity
            .created(URI.create("/api/control/orders/delivery-methods/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/orders/delivery-methods/{id}")
    @AdminOnly
    public ResponseEntity<DeliveryMethodResponse> update(
        @PathVariable long id,
        @Valid @RequestBody DeliveryMethodRequest request
    ) {
        return ResponseEntity.ok(deliveryMethodService.update(id, request));
    }

    /**
     * Deactivate, not delete — past orders reference the row, so it has to stay resolvable. The
     * verb is DELETE because that is what the admin means; what happens underneath is retirement.
     */
    @DeleteMapping("/api/control/orders/delivery-methods/{id}")
    @AdminOnly
    public ResponseEntity<DeliveryMethodResponse> deactivate(@PathVariable long id) {
        return ResponseEntity.ok(deliveryMethodService.deactivate(id));
    }
}
