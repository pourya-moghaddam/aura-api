package com.aura.catalog.product;

import com.aura.catalog.product.dto.ProductMediaRequest;
import com.aura.catalog.product.dto.ProductMediaResponse;
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
@RequestMapping("/api/control/catalog/products/{productId}/media")
@SellerOnly
public class ProductMediaController {

    private final ProductMediaService productMediaService;

    @GetMapping
    public ResponseEntity<List<ProductMediaResponse>> list(@PathVariable long productId) {
        return ResponseEntity.ok(productMediaService.listFor(CurrentUser.requiredId(), productId));
    }

    @PostMapping
    public ResponseEntity<ProductMediaResponse> attach(
        @PathVariable long productId,
        @Valid @RequestBody ProductMediaRequest request
    ) {
        ProductMediaResponse created =
            productMediaService.attach(CurrentUser.requiredId(), productId, request);
        return ResponseEntity
            .created(URI.create("/api/control/catalog/products/" + productId + "/media/" + created.id()))
            .body(created);
    }

    @PostMapping("/{productMediaId}/primary")
    public ResponseEntity<ProductMediaResponse> makePrimary(
        @PathVariable long productId,
        @PathVariable long productMediaId
    ) {
        return ResponseEntity.ok(
            productMediaService.makePrimary(CurrentUser.requiredId(), productId, productMediaId));
    }

    @PutMapping("/{productMediaId}/sort-order")
    public ResponseEntity<ProductMediaResponse> reorder(
        @PathVariable long productId,
        @PathVariable long productMediaId,
        @RequestParam int sortOrder
    ) {
        return ResponseEntity.ok(
            productMediaService.reorder(CurrentUser.requiredId(), productId, productMediaId, sortOrder));
    }

    @DeleteMapping("/{productMediaId}")
    public ResponseEntity<Void> detach(
        @PathVariable long productId,
        @PathVariable long productMediaId
    ) {
        productMediaService.detach(CurrentUser.requiredId(), productId, productMediaId);
        return ResponseEntity.noContent().build();
    }
}
