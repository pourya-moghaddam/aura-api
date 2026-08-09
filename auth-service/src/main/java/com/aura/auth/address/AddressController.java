package com.aura.auth.address;

import com.aura.auth.address.dto.AddressRequest;
import com.aura.auth.address.dto.AddressResponse;
import com.aura.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * The signed-in user's own address book.
 *
 * <p>No id of any kind is accepted from the request body — the owner always comes from the token via
 * {@link CurrentUser}. Every route is authenticated by the catch-all rule in {@code SecurityConfig},
 * so there is no per-method annotation here.
 */
@RestController
@RequestMapping("/api/auth/users/me/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ResponseEntity<List<AddressResponse>> list() {
        return ResponseEntity.ok(addressService.listFor(CurrentUser.requiredId()));
    }

    @GetMapping("/{addressId}")
    public ResponseEntity<AddressResponse> get(@PathVariable long addressId) {
        return ResponseEntity.ok(addressService.getFor(CurrentUser.requiredId(), addressId));
    }

    @PostMapping
    public ResponseEntity<AddressResponse> create(@Valid @RequestBody AddressRequest request) {
        AddressResponse created = addressService.create(CurrentUser.requiredId(), request);
        return ResponseEntity
            .created(URI.create("/api/auth/users/me/addresses/" + created.id()))
            .body(created);
    }

    @PutMapping("/{addressId}")
    public ResponseEntity<AddressResponse> update(
        @PathVariable long addressId,
        @Valid @RequestBody AddressRequest request
    ) {
        return ResponseEntity.ok(addressService.update(CurrentUser.requiredId(), addressId, request));
    }

    /**
     * Promoting a default is its own endpoint rather than a flag on update, so the UI's "make this
     * my default" action does not have to round-trip and resubmit every other field to do it.
     */
    @PostMapping("/{addressId}/default")
    public ResponseEntity<AddressResponse> makeDefault(@PathVariable long addressId) {
        return ResponseEntity.ok(addressService.makeDefault(CurrentUser.requiredId(), addressId));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(@PathVariable long addressId) {
        addressService.delete(CurrentUser.requiredId(), addressId);
        return ResponseEntity.noContent().build();
    }
}
