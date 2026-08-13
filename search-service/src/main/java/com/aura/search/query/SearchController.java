package com.aura.search.query;

import com.aura.search.query.dto.SearchQuery;
import com.aura.search.query.dto.SearchResults;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

/**
 * Storefront search — requirement 9.
 *
 * <p>Anonymous, and a GET with query parameters rather than a POST with a body: a search is a
 * page a shopper can bookmark, share and go back to, and all three stop working the moment the
 * query lives in a request body.
 */
@RestController
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService searchService;

    @GetMapping("/api/search/products")
    public ResponseEntity<SearchResults> search(@Valid @ModelAttribute SearchQuery query) {
        return ResponseEntity.ok(searchService.search(query));
    }
}
