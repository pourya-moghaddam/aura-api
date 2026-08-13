package com.aura.search.query;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.aura.search.query.dto.SearchQuery;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shopper's sidebar selections, as Elasticsearch filters.
 *
 * <p>Split from the text query because the two are combined differently. The text query decides
 * relevance; these decide membership, contribute nothing to a score, and — crucially — have to be
 * applied selectively when counting facets. Keeping them addressable one at a time is what makes
 * the post-filter pattern expressible at all.
 */
@Component
public class FilterBuilder {

    /**
     * Every selection except the named facet's own.
     *
     * <p>{@code exclude} is what makes a facet count the alternatives a shopper could switch to
     * rather than only the one they already picked.
     */
    public List<Query> selections(SearchQuery request, Facet exclude) {
        List<Query> filters = new ArrayList<>();

        if (exclude != Facet.CATEGORY) {
            categoryFilter(request).ifPresent(filters::add);
        }
        if (exclude != Facet.COLOR) {
            termsFilter("colorNames", request.colors()).ifPresent(filters::add);
        }
        if (exclude != Facet.SIZE) {
            termsFilter("sizeNames", request.sizes()).ifPresent(filters::add);
        }

        // Price and availability have no facet of their own to exclude - a price histogram is
        // computed over the range the shopper has *not* narrowed, which is the unfiltered set.
        priceFilter(request).ifPresent(filters::add);
        stockFilter(request).ifPresent(filters::add);
        filters.addAll(attributeFilters(request, null));

        return filters;
    }

    /** Every selection, for the hits themselves. */
    public List<Query> selections(SearchQuery request) {
        return selections(request, null);
    }

    /** Every selection except one attribute's own, for that attribute's facet. */
    public List<Query> selectionsExceptAttribute(SearchQuery request, String attribute) {
        List<Query> filters = new ArrayList<>();

        categoryFilter(request).ifPresent(filters::add);
        termsFilter("colorNames", request.colors()).ifPresent(filters::add);
        termsFilter("sizeNames", request.sizes()).ifPresent(filters::add);
        priceFilter(request).ifPresent(filters::add);
        stockFilter(request).ifPresent(filters::add);
        filters.addAll(attributeFilters(request, attribute));

        return filters;
    }

    /**
     * A category and everything beneath it.
     *
     * <p>One term against the indexed ancestor path. The alternative — asking catalog for the
     * subtree and listing its ids — would put a network call in front of every browse and go stale
     * the moment an admin moved a category.
     */
    private Optional<Query> categoryFilter(SearchQuery request) {
        return Optional.ofNullable(request.categoryId())
            .map(id -> Query.of(q -> q.term(t -> t.field("categoryPath").value(id))));
    }

    private Optional<Query> termsFilter(String field, List<String> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        // Several values of one facet are an OR: picking Navy and Black means either, not both,
        // because no product is two colours at once in the way a shopper means it.
        List<FieldValue> terms = values.stream()
            .map(value -> FieldValue.of(value.toLowerCase(java.util.Locale.ROOT)))
            .toList();

        return Optional.of(Query.of(q -> q.terms(t -> t
            .field(field)
            .terms(v -> v.value(terms)))));
    }

    private Optional<Query> priceFilter(SearchQuery request) {
        if (!request.hasPriceRange()) {
            return Optional.empty();
        }
        // Against minPrice: a product spanning 100,000 to 900,000 should appear under "up to
        // 200,000", because something in it can be bought for that.
        return Optional.of(Query.of(q -> q.range(r -> r.number(n -> {
            n.field("minPrice");
            if (request.minPrice() != null) {
                n.gte(request.minPrice().doubleValue());
            }
            if (request.maxPrice() != null) {
                n.lte(request.maxPrice().doubleValue());
            }
            return n;
        }))));
    }

    private Optional<Query> stockFilter(SearchQuery request) {
        // Only when asked for. Filtering out-of-stock by default would hide products a shopper
        // might still want to see and wait for, and that is a merchandising decision rather than
        // a search one.
        return Boolean.TRUE.equals(request.inStock())
            ? Optional.of(Query.of(q -> q.term(t -> t.field("inStock").value(true))))
            : Optional.empty();
    }

    /**
     * Dynamic field selections.
     *
     * <p>Different attributes are an AND — "leather" and "made in Iran" both have to hold — while
     * several values of one attribute are an OR. That asymmetry is what a shopper means by ticking
     * boxes, and getting it backwards makes the sidebar return nothing as soon as two boxes are
     * ticked.
     */
    private List<Query> attributeFilters(SearchQuery request, String exclude) {
        List<Query> filters = new ArrayList<>();

        for (Map.Entry<String, List<String>> selection : request.attributes().entrySet()) {
            if (selection.getKey().equals(exclude) || selection.getValue() == null
                || selection.getValue().isEmpty()) {
                continue;
            }
            termsFilter("attributes." + selection.getKey(), selection.getValue())
                .ifPresent(filters::add);
        }
        return filters;
    }
}
