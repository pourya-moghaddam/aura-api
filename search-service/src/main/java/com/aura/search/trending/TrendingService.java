package com.aura.search.trending;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.ProductDocument;
import com.aura.search.query.dto.SearchHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What is selling, as a storefront list.
 *
 * <p>Two stores, on purpose: Redis knows how much each product sold, Elasticsearch knows what each
 * product <em>is</em>. Neither can answer alone, and keeping the counts out of the index is what
 * lets the indexer keep replacing documents wholesale.
 *
 * <p>The ranking comes from Redis and the ordering is preserved through the lookup, because
 * Elasticsearch returns documents in its own order and a "best sellers" list sorted by relevance
 * to nothing is just a list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingService {

    private final SalesCounter salesCounter;
    private final ElasticsearchClient client;
    private final SearchProperties properties;

    public List<SearchHit> trending(int limit) {
        // More ids than asked for: some will have been withdrawn since they sold, and asking for
        // exactly the limit would return a short list rather than the next best sellers.
        List<Long> ranked = salesCounter.topProducts(limit * 2);
        if (ranked.isEmpty()) {
            return List.of();
        }

        try {
            Map<Long, ProductDocument> found = client.search(s -> s
                    .index(properties.alias())
                    .size(ranked.size())
                    .query(q -> q.bool(b -> b
                        .filter(f -> f.terms(t -> t
                            .field("productId")
                            .terms(v -> v.value(ranked.stream()
                                .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                .toList()))))
                        // Something that sold well and has since been withdrawn is not trending,
                        // it is a dead link.
                        .filter(f -> f.term(t -> t.field("status").value("ACTIVE"))))),
                    ProductDocument.class)
                .hits().hits().stream()
                .filter(hit -> hit.source() != null)
                .map(co.elastic.clients.elasticsearch.core.search.Hit::source)
                .collect(Collectors.toMap(ProductDocument::productId, Function.identity(),
                    (first, second) -> first));

            return ranked.stream()
                .filter(found::containsKey)
                .limit(limit)
                .map(id -> SearchHit.from(found.get(id), null))
                .toList();

        } catch (IOException | RuntimeException e) {
            // A homepage without a trending strip is a smaller problem than a homepage that fails
            // to load, and unlike search there is no query a shopper is waiting on an answer to.
            log.error("Could not build the trending list", e);
            return List.of();
        }
    }
}
