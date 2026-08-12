package com.aura.catalog.product;

import com.aura.catalog.product.dto.VariantRequest;
import com.aura.catalog.product.dto.VariantResponse;
import com.aura.common.security.CurrentUser;
import com.aura.common.security.SellerOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/control/catalog/products/{productId}/variants")
@SellerOnly
public class ProductVariantController {

    private final ProductVariantService productVariantService;

    @GetMapping
    public ResponseEntity<List<VariantResponse>> list(@PathVariable long productId) {
        return ResponseEntity.ok(productVariantService.listFor(CurrentUser.requiredId(), productId));
    }

    @PostMapping
    public ResponseEntity<VariantResponse> add(
        @PathVariable long productId,
        @Valid @RequestBody VariantRequest request
    ) {
        VariantResponse created = productVariantService.add(CurrentUser.requiredId(), productId, request);
        return ResponseEntity
            .created(URI.create("/api/control/catalog/products/" + productId + "/variants/" + created.id()))
            .body(created);
    }

    @PutMapping("/{variantId}")
    public ResponseEntity<VariantResponse> update(
        @PathVariable long productId,
        @PathVariable long variantId,
        @Valid @RequestBody VariantRequest request
    ) {
        return ResponseEntity.ok(
            productVariantService.update(CurrentUser.requiredId(), productId, variantId, request));
    }

    @DeleteMapping("/{variantId}")
    public ResponseEntity<Void> delete(@PathVariable long productId, @PathVariable long variantId) {
        productVariantService.delete(CurrentUser.requiredId(), productId, variantId);
        return ResponseEntity.noContent().build();
    }
}
