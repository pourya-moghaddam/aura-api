package com.aura.catalog.category;

import com.aura.catalog.category.dto.CategoryRequest;
import com.aura.catalog.category.dto.CategoryResponse;
import com.aura.common.security.CatalogAdminOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * Categories are read by everyone and written only by admins, so the two surfaces are separate
 * controllers on separate paths rather than one class with mixed annotations — the gateway routes
 * {@code /api/control/**} differently, and mixing them would put a public read behind a control
 * route or the reverse.
 */
@RestController
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    // --- storefront (public) -------------------------------------------------------------------

    /** The navigation tree. One query, nested in memory. */
    @GetMapping("/api/catalog/categories")
    public ResponseEntity<List<CategoryResponse>> tree() {
        return ResponseEntity.ok(categoryService.listTree());
    }

    /** Leaves only — what the seller UI offers when picking a product's category. */
    @GetMapping("/api/catalog/categories/leaves")
    public ResponseEntity<List<CategoryResponse>> leaves() {
        return ResponseEntity.ok(categoryService.listLeaves());
    }

    @GetMapping("/api/catalog/categories/{slug}")
    public ResponseEntity<CategoryResponse> bySlug(@PathVariable String slug) {
        return ResponseEntity.ok(categoryService.getBySlug(slug));
    }

    // --- control panel (ADMIN) -----------------------------------------------------------------

    @PostMapping("/api/control/catalog/categories")
    @CatalogAdminOnly
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
        CategoryResponse created = categoryService.create(request);
        return ResponseEntity.created(URI.create("/api/catalog/categories/" + created.slug()))
            .body(created);
    }

    @PutMapping("/api/control/catalog/categories/{id}")
    @CatalogAdminOnly
    public ResponseEntity<CategoryResponse> update(
        @PathVariable long id,
        @Valid @RequestBody CategoryRequest request
    ) {
        return ResponseEntity.ok(categoryService.update(id, request));
    }

    @DeleteMapping("/api/control/catalog/categories/{id}")
    @CatalogAdminOnly
    public ResponseEntity<Void> delete(@PathVariable long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
