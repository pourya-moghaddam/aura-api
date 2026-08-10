package com.aura.catalog.size;

import com.aura.catalog.size.dto.SizeRequest;
import com.aura.catalog.size.dto.SizeResponse;
import com.aura.common.security.CatalogAdminOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class SizeController {

    private final SizeService sizeService;

    @GetMapping("/api/catalog/sizes")
    public ResponseEntity<List<SizeResponse>> listActive() {
        return ResponseEntity.ok(sizeService.listActive());
    }

    /** What the seller's variant form asks for once a category is chosen. */
    @GetMapping("/api/catalog/categories/{categoryId}/sizes")
    public ResponseEntity<List<SizeResponse>> listForCategory(@PathVariable long categoryId) {
        return ResponseEntity.ok(sizeService.listForCategory(categoryId));
    }

    @GetMapping("/api/control/catalog/sizes")
    @CatalogAdminOnly
    public ResponseEntity<List<SizeResponse>> listAll() {
        return ResponseEntity.ok(sizeService.listAll());
    }

    @PostMapping("/api/control/catalog/sizes")
    @CatalogAdminOnly
    public ResponseEntity<SizeResponse> create(@Valid @RequestBody SizeRequest request) {
        SizeResponse created = sizeService.create(request);
        return ResponseEntity.created(URI.create("/api/control/catalog/sizes/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/catalog/sizes/{id}")
    @CatalogAdminOnly
    public ResponseEntity<SizeResponse> update(
        @PathVariable long id,
        @Valid @RequestBody SizeRequest request
    ) {
        return ResponseEntity.ok(sizeService.update(id, request));
    }

    @DeleteMapping("/api/control/catalog/sizes/{id}")
    @CatalogAdminOnly
    public ResponseEntity<Void> delete(@PathVariable long id) {
        sizeService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
