package com.aura.search.trending;

import com.aura.search.query.dto.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The best sellers, for a storefront homepage.
 */
@RestController
@RequiredArgsConstructor
public class TrendingController {

    private static final int MAX_LIMIT = 50;

    private final TrendingService trendingService;

    @GetMapping("/api/search/trending")
    public ResponseEntity<List<SearchHit>> trending(
        @RequestParam(required = false) Integer limit
    ) {
        int capped = limit == null ? 12 : Math.min(Math.max(limit, 1), MAX_LIMIT);

        return ResponseEntity
            // Popularity moves over days, not seconds. A cache header here saves the homepage
            // hitting Redis and Elasticsearch for every visitor to show the same twelve products.
            .ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(trendingService.trending(capped));
    }
}
