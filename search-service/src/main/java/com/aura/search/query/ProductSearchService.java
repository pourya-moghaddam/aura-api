package com.aura.search.query;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.ProductDocument;
import com.aura.search.query.dto.Facets;
import com.aura.search.query.dto.SearchHit;
import com.aura.search.query.dto.SearchQuery;
import com.aura.search.query.dto.SearchResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/**
 * Runs a search and turns the answer into something a storefront can render.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private final ProductQueryBuilder queryBuilder;
    private final FilterBuilder filterBuilder;
    private final FacetBuilder facetBuilder;

    public SearchResults search(SearchQuery request) {
        SearchRequest search = SearchRequest.of(s -> s
            .index(properties.alias())
            .query(q -> q.bool(b -> b
                .must(queryBuilder.build(request))
                // A filter, not a must: the status contributes nothing to relevance and this way
                // Elasticsearch can cache it.
                .filter(queryBuilder.filters())))
            // The sidebar selections go here rather than in the query, so they narrow the hits
            // while the aggregations still see everything the text matched. That is the whole
            // point of a post filter, and without it every unselected facet reports zero.
            .postFilter(p -> p.bool(b -> b.filter(filterBuilder.selections(request))))
            .aggregations(facetBuilder.aggregations(request))
            .sort(request.sort().options())
            .from(request.from())
            .size(request.size())
            // Exact totals up to a point. Counting every match of a broad query costs more than
            // the answer is worth, and the response says whether the number is exact.
            .trackTotalHits(t -> t.count(10_000)));

        try {
            SearchResponse<ProductDocument> response = client.search(search, ProductDocument.class);

            List<SearchHit> hits = response.hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(hit -> SearchHit.from(hit.source(), hit.score()))
                .toList();

            long total = response.hits().total() == null ? hits.size()
                : response.hits().total().value();
            boolean exhausted = response.hits().total() != null
                && "eq".equals(response.hits().total().relation().jsonValue());

            Facets facets = facetBuilder.read(response.aggregations(), request);

            return new SearchResults(hits, total, exhausted, request.page(), request.size(), facets);

        } catch (IOException | RuntimeException e) {
            // Deliberately not an empty page. "No results" and "search is broken" look identical
            // to a shopper and completely different to whoever has to fix it, and a storefront
            // that quietly shows an empty catalogue during an outage is the worse of the two.
            log.error("Search failed for query '{}'", request.q(), e);
            throw new BusinessRuleException("search-unavailable",
                "Search is not available right now. Please try again shortly.");
        }
    }
}
