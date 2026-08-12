package com.aura.catalog.product;

import com.aura.catalog.product.dto.ProductRequest;
import com.aura.catalog.product.dto.ProductResponse;
import com.aura.common.security.CurrentUser;
import com.aura.common.security.SellerOnly;
import com.aura.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // --- storefront ----------------------------------------------------------------------------

    @GetMapping("/api/catalog/products/{slug}")
    public ResponseEntity<ProductResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(productService.getPublishedBySlug(slug));
    }

    /**
     * Requirement 13. Offset pagination rather than a cursor because the UI shows page numbers.
     *
     * <p>Sort is a named option rather than a free {@code Sort} parameter: the query underneath is
     * native, and Spring appends a sort to native SQL without translating property names — see
     * {@link ProductSort}.
     */
    @GetMapping("/api/catalog/categories/{categoryId}/products")
    public ResponseEntity<PageResponse<ProductResponse>> listInCategory(
        @PathVariable long categoryId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "24") int size,
        @RequestParam(defaultValue = "NEWEST") ProductSort sort
    ) {
        return ResponseEntity.ok(
            productService.listPublishedInCategory(categoryId, sort.toPageable(page, size)));
    }

    // --- control panel -------------------------------------------------------------------------

    /**
     * The seller's own catalogue, most recently touched first — which is the order they want when
     * they come back to finish a draft. Ordering is fixed by the query, so no sort parameter here
     * to reject or mistranslate.
     */
    @GetMapping("/api/control/catalog/products")
    @SellerOnly
    public ResponseEntity<PageResponse<ProductResponse>> listMine(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        return ResponseEntity.ok(productService.listForSeller(CurrentUser.requiredId(), pageable));
    }

    @GetMapping("/api/control/catalog/products/{id}")
    @SellerOnly
    public ResponseEntity<ProductResponse> getMine(@PathVariable long id) {
        return ResponseEntity.ok(productService.getForSeller(CurrentUser.requiredId(), id));
    }

    @PostMapping("/api/control/catalog/products")
    @SellerOnly
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(CurrentUser.requiredId(), request);
        return ResponseEntity.created(URI.create("/api/control/catalog/products/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/catalog/products/{id}")
    @SellerOnly
    public ResponseEntity<ProductResponse> update(
        @PathVariable long id,
        @Valid @RequestBody ProductRequest request
    ) {
        return ResponseEntity.ok(productService.update(CurrentUser.requiredId(), id, request));
    }

    @PostMapping("/api/control/catalog/products/{id}/publish")
    @SellerOnly
    public ResponseEntity<ProductResponse> publish(@PathVariable long id) {
        return ResponseEntity.ok(productService.publish(CurrentUser.requiredId(), id));
    }

    @PostMapping("/api/control/catalog/products/{id}/archive")
    @SellerOnly
    public ResponseEntity<ProductResponse> archive(@PathVariable long id) {
        return ResponseEntity.ok(productService.archive(CurrentUser.requiredId(), id));
    }

    @DeleteMapping("/api/control/catalog/products/{id}")
    @SellerOnly
    public ResponseEntity<Void> delete(@PathVariable long id) {
        productService.delete(CurrentUser.requiredId(), id);
        return ResponseEntity.noContent().build();
    }
}
