package com.aura.catalog.color;

import com.aura.catalog.color.dto.ColorRequest;
import com.aura.catalog.color.dto.ColorResponse;
import com.aura.common.security.CatalogAdminOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class ColorController {

    private final ColorService colorService;

    /**
     * Public: the storefront's colour filter needs these, and so does the seller's variant form.
     * Active only — a retired colour should not appear in either.
     */
    @GetMapping("/api/catalog/colors")
    public ResponseEntity<List<ColorResponse>> listActive() {
        return ResponseEntity.ok(colorService.listActive());
    }

    /** Admin listing includes retired colours, which the public one deliberately hides. */
    @GetMapping("/api/control/catalog/colors")
    @CatalogAdminOnly
    public ResponseEntity<List<ColorResponse>> listAll() {
        return ResponseEntity.ok(colorService.listAll());
    }

    @PostMapping("/api/control/catalog/colors")
    @CatalogAdminOnly
    public ResponseEntity<ColorResponse> create(@Valid @RequestBody ColorRequest request) {
        ColorResponse created = colorService.create(request);
        return ResponseEntity.created(URI.create("/api/control/catalog/colors/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/catalog/colors/{id}")
    @CatalogAdminOnly
    public ResponseEntity<ColorResponse> update(
        @PathVariable long id,
        @Valid @RequestBody ColorRequest request
    ) {
        return ResponseEntity.ok(colorService.update(id, request));
    }

    @DeleteMapping("/api/control/catalog/colors/{id}")
    @CatalogAdminOnly
    public ResponseEntity<Void> delete(@PathVariable long id) {
        colorService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
