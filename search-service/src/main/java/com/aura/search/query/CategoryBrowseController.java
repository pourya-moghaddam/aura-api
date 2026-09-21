package com.aura.search.query;

import com.aura.search.query.dto.SearchQuery;
import com.aura.search.query.dto.SearchResults;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The category page — requirement 13.
 *
 * <p>Deliberately the same machinery as search rather than a second implementation: browsing is a
 * search with no text and a category filter, and the sorting, paging and facets a category page
 * needs are the ones search already has. A separate query path would drift, and the way it drifts
 * is that a filter works on one page and not the other.
 *
 * <p>What it adds is the URL. A category id in the path is bookmarkable, shareable and cacheable
 * in a way a query parameter beside a dozen others is not, and it lets the storefront treat
 * "browse this category" as a route rather than as a search it has to assemble.
 */
@RestController
@RequiredArgsConstructor
public class CategoryBrowseController {

    private final ProductSearchService searchService;

    @GetMapping("/api/search/categories/{categoryId}/products")
    public ResponseEntity<SearchResults> browse(
        @PathVariable long categoryId,
        @Valid @ModelAttribute SearchQuery query
    ) {
        return ResponseEntity.ok(searchService.search(query.browsing(categoryId)));
    }
}
