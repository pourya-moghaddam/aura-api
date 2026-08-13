package com.aura.search.query;

import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.aura.search.query.dto.FacetValue;
import com.aura.search.query.dto.Facets;
import com.aura.search.query.dto.SearchQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The sidebar, counted correctly.
 *
 * <p>The whole difficulty is one sentence: <strong>a facet's counts must exclude that facet's own
 * filter.</strong> Aggregate naively under the same filters as the hits and choosing Navy makes
 * every other colour report zero — so the shopper can narrow but never widen, and the sidebar
 * becomes a one-way door they have to use the back button to escape.
 *
 * <p>The fix is Elasticsearch's post-filter pattern, in two halves:
 *
 * <ul>
 *   <li>the shopper's selections go in a {@code post_filter}, which narrows the hits but leaves
 *       the aggregations looking at everything the text query matched;</li>
 *   <li>each facet is then wrapped in a {@code filter} aggregation carrying every <em>other</em>
 *       selection, so its counts reflect the rest of the sidebar but not itself.</li>
 * </ul>
 *
 * <p>Price is the exception and deliberately so: it has no tick-boxes to keep alive, so its range
 * is computed under everything.
 */
@Component
@RequiredArgsConstructor
public class FacetBuilder {

    /** Enough values for a sidebar. Beyond this a shopper is searching, not browsing. */
    private static final int MAX_VALUES = 50;

    private static final String PRICE_KEY = "price";
    private static final String FILTERED = "filtered";

    private final FilterBuilder filterBuilder;

    /** One filtered aggregation per facet, plus the price range. */
    public Map<String, Aggregation> aggregations(SearchQuery request) {
        Map<String, Aggregation> aggregations = new LinkedHashMap<>();

        for (Facet facet : Facet.all()) {
            aggregations.put(facet.responseKey(),
                filtered(filterBuilder.selections(request, facet), terms(facet.field())));
        }

        for (String attribute : request.facetFields()) {
            aggregations.put(attributeKey(attribute),
                filtered(filterBuilder.selectionsExceptAttribute(request, attribute),
                    terms("attributes." + attribute)));
        }

        // Under every selection, because a price slider narrows to what is left rather than
        // offering alternatives to switch between.
        aggregations.put(PRICE_KEY, Aggregation.of(a -> a
            .filter(f -> f.bool(b -> b.filter(filterBuilder.selections(request))))
            .aggregations("min", min -> min.min(m -> m.field("minPrice")))
            .aggregations("max", max -> max.max(m -> m.field("maxPrice")))));

        return aggregations;
    }

    public Facets read(Map<String, Aggregate> aggregates, SearchQuery request) {
        Map<String, List<FacetValue>> fields = new LinkedHashMap<>();

        for (Facet facet : Facet.all()) {
            fields.put(facet.responseKey(),
                values(aggregates, facet.responseKey(), selectedFor(facet, request)));
        }

        Map<String, List<FacetValue>> attributes = new LinkedHashMap<>();
        for (String attribute : request.facetFields()) {
            attributes.put(attribute, values(aggregates, attributeKey(attribute),
                lowercase(request.attributes().getOrDefault(attribute, List.of()))));
        }

        return new Facets(fields, attributes, priceRange(aggregates));
    }

    private Aggregation filtered(List<Query> filters, Aggregation inner) {
        return Aggregation.of(a -> a
            .filter(f -> f.bool(b -> b.filter(filters)))
            .aggregations(FILTERED, inner));
    }

    private Aggregation terms(String field) {
        return Aggregation.of(a -> a.terms(t -> t.field(field).size(MAX_VALUES)));
    }

    private List<FacetValue> values(Map<String, Aggregate> aggregates, String key,
                                    Set<String> selected) {
        Aggregate outer = aggregates.get(key);
        if (outer == null || !outer.isFilter()) {
            return List.of();
        }

        Aggregate inner = outer.filter().aggregations().get(FILTERED);
        if (inner == null) {
            return List.of();
        }

        List<FacetValue> values = new ArrayList<>();
        if (inner.isSterms()) {
            for (StringTermsBucket bucket : inner.sterms().buckets().array()) {
                String value = bucket.key().stringValue();
                values.add(new FacetValue(value, bucket.docCount(), selected.contains(value)));
            }
        } else if (inner.isLterms()) {
            // categoryPath is numeric, so its buckets are longs rather than strings.
            inner.lterms().buckets().array().forEach(bucket ->
                values.add(new FacetValue(String.valueOf(bucket.key()), bucket.docCount(),
                    selected.contains(String.valueOf(bucket.key())))));
        }
        return values;
    }

    private Facets.PriceRange priceRange(Map<String, Aggregate> aggregates) {
        Aggregate price = aggregates.get(PRICE_KEY);
        if (price == null || !price.isFilter()) {
            return new Facets.PriceRange(null, null);
        }

        // Off the document count, not off the value. Elasticsearch reports 0 for a min over an
        // empty set, which is indistinguishable from a product that genuinely costs nothing - and
        // a free gift in the catalogue would otherwise erase the slider's lower bound.
        if (price.filter().docCount() == 0) {
            return new Facets.PriceRange(null, null);
        }

        Aggregate min = price.filter().aggregations().get("min");
        Aggregate max = price.filter().aggregations().get("max");

        return new Facets.PriceRange(bound(min == null ? null : min.min().value()),
            bound(max == null ? null : max.max().value()));
    }

    /** Guards the remaining non-finite answers rather than trusting the aggregate blindly. */
    private Long bound(Double value) {
        return value == null || value.isNaN() || value.isInfinite() ? null : value.longValue();
    }

    private Set<String> selectedFor(Facet facet, SearchQuery request) {
        return switch (facet) {
            case COLOR -> lowercase(request.colors());
            case SIZE -> lowercase(request.sizes());
            case CATEGORY -> request.categoryId() == null
                ? Set.of() : Set.of(String.valueOf(request.categoryId()));
        };
    }

    /** The index normalises keyword facets to lower case, so a selection has to be compared that way. */
    private Set<String> lowercase(List<String> values) {
        return values.stream().map(v -> v.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    }

    /** Namespaced so an attribute called "colors" cannot collide with the colour facet. */
    private String attributeKey(String attribute) {
        return "attr_" + attribute;
    }
}
