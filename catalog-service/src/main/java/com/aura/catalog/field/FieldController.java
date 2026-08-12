package com.aura.catalog.field;

import com.aura.catalog.field.dto.FieldRequest;
import com.aura.catalog.field.dto.FieldResponse;
import com.aura.catalog.field.dto.FieldValueRequest;
import com.aura.catalog.field.dto.FieldValueResponse;
import com.aura.common.security.CatalogAdminOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class FieldController {

    private final FieldService fieldService;

    /**
     * What the seller's product form asks for once a leaf category is chosen: the category's own
     * fields plus everything inherited from its ancestors.
     */
    @GetMapping("/api/catalog/categories/{categoryId}/fields")
    public ResponseEntity<List<FieldResponse>> listEffective(@PathVariable long categoryId) {
        return ResponseEntity.ok(fieldService.listEffectiveFor(categoryId));
    }

    /**
     * Only the fields attached directly to this category — what the admin screen for it edits.
     * Distinct from the storefront view above, which includes inherited fields the admin must not
     * edit from here.
     */
    @GetMapping("/api/control/catalog/categories/{categoryId}/fields")
    @CatalogAdminOnly
    public ResponseEntity<List<FieldResponse>> listOwned(@PathVariable long categoryId) {
        return ResponseEntity.ok(fieldService.listOwnedBy(categoryId));
    }

    @PostMapping("/api/control/catalog/fields")
    @CatalogAdminOnly
    public ResponseEntity<FieldResponse> create(@Valid @RequestBody FieldRequest request) {
        FieldResponse created = fieldService.create(request);
        return ResponseEntity.created(URI.create("/api/control/catalog/fields/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/catalog/fields/{id}")
    @CatalogAdminOnly
    public ResponseEntity<FieldResponse> update(
        @PathVariable long id,
        @Valid @RequestBody FieldRequest request
    ) {
        return ResponseEntity.ok(fieldService.update(id, request));
    }

    @DeleteMapping("/api/control/catalog/fields/{id}")
    @CatalogAdminOnly
    public ResponseEntity<Void> delete(@PathVariable long id) {
        fieldService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/catalog/fields/{fieldId}/values")
    public ResponseEntity<List<FieldValueResponse>> listValues(@PathVariable long fieldId) {
        return ResponseEntity.ok(fieldService.listValues(fieldId));
    }

    @PostMapping("/api/control/catalog/fields/{fieldId}/values")
    @CatalogAdminOnly
    public ResponseEntity<FieldValueResponse> addValue(
        @PathVariable long fieldId,
        @Valid @RequestBody FieldValueRequest request
    ) {
        FieldValueResponse created = fieldService.addValue(fieldId, request);
        return ResponseEntity
            .created(URI.create("/api/control/catalog/fields/" + fieldId + "/values/" + created.id()))
            .body(created);
    }

    @DeleteMapping("/api/control/catalog/fields/{fieldId}/values/{valueId}")
    @CatalogAdminOnly
    public ResponseEntity<Void> deleteValue(
        @PathVariable long fieldId,
        @PathVariable long valueId
    ) {
        fieldService.deleteValue(fieldId, valueId);
        return ResponseEntity.noContent().build();
    }
}
