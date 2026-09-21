package com.aura.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.aura.search.config.SearchProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;

/**
 * Creates the index and points the alias at it, if nothing is there yet.
 *
 * <p>Everything else in this service reads and writes through the <em>alias</em>, never an index
 * name. That indirection is the whole reason a mapping change is survivable: the new mapping goes
 * into {@code products_v2}, the documents are rebuilt into it, and the alias is moved in a single
 * atomic call. Nothing else in the codebase learns that it happened.
 *
 * <p>Deliberately does nothing when the alias already exists. Re-applying settings to a live index
 * is not possible for analysis changes anyway — Elasticsearch refuses them on an open index — and
 * silently trying would turn a startup into a confusing failure. A mapping change is a reindex,
 * which is its own deliberate operation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexBootstrapper {

    private final ElasticsearchClient client;
    private final IndexDefinition definition;
    private final SearchProperties properties;

    @PostConstruct
    public void createIndexIfMissing() {
        if (!properties.indexBootstrap()) {
            log.info("Index bootstrap is disabled; expecting '{}' to exist already",
                properties.alias());
            return;
        }

        try {
            if (client.indices().existsAlias(a -> a.name(properties.alias())).value()) {
                log.info("Alias '{}' already exists", properties.alias());
                return;
            }

            String index = properties.indexName(1);
            client.indices().create(CreateIndexRequest.of(builder -> builder
                .index(index)
                // Settings and mappings come from the JSON, and the alias is attached in the same
                // call - so there is never a moment where the index exists but nothing can reach it.
                .withJson(new StringReader(definition.json()))
                .aliases(properties.alias(), alias -> alias)));

            log.info("Created index '{}' behind alias '{}'", index, properties.alias());

        } catch (IOException | RuntimeException e) {
            // Not fatal on purpose. Elasticsearch may simply be slower to start than this service,
            // and a crash-loop helps nobody; the index health check reports the gap, and search
            // fails loudly per request until it closes.
            log.error("Could not create the search index. Search will not work until it exists.", e);
        }
    }
}
