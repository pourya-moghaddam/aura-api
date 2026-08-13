package com.aura.catalog.banner;

import com.aura.catalog.banner.dto.BannerRequest;
import com.aura.catalog.banner.dto.BannerResponse;
import com.aura.common.security.AdminOnly;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;

@RestController
@RequiredArgsConstructor
public class BannerController {

    private final BannerService bannerService;

    /**
     * The homepage slider. Every visitor hits this, and the content changes rarely, so it carries
     * a short cache header — long enough to matter under load, short enough that switching a
     * banner off takes effect in about a minute rather than whenever caches happen to expire.
     */
    @GetMapping("/api/catalog/banners")
    public ResponseEntity<List<BannerResponse>> listLive() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
            .body(bannerService.listLive());
    }

    @GetMapping("/api/control/catalog/banners")
    @AdminOnly
    public ResponseEntity<List<BannerResponse>> listAll() {
        return ResponseEntity.ok(bannerService.listAll());
    }

    @PostMapping("/api/control/catalog/banners")
    @AdminOnly
    public ResponseEntity<BannerResponse> create(@Valid @RequestBody BannerRequest request) {
        BannerResponse created = bannerService.create(request);
        return ResponseEntity.created(URI.create("/api/control/catalog/banners/" + created.id()))
            .body(created);
    }

    @PutMapping("/api/control/catalog/banners/{id}")
    @AdminOnly
    public ResponseEntity<BannerResponse> update(
        @PathVariable long id,
        @Valid @RequestBody BannerRequest request
    ) {
        return ResponseEntity.ok(bannerService.update(id, request));
    }

    @DeleteMapping("/api/control/catalog/banners/{id}")
    @AdminOnly
    public ResponseEntity<Void> delete(@PathVariable long id) {
        bannerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
