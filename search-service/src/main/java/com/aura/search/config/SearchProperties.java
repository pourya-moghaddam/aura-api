package com.aura.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param uri       where Elasticsearch is. No credentials here: development runs with security
 *                  off, and production supplies them through the environment.
 * @param alias     what everything reads and writes through. Never an index name — the whole point
 *                  of the alias is that a mapping change is a reindex into a new index and an
 *                  atomic flip, with nothing else needing to know.
 * @param indexBootstrap whether to create the index at startup when it is missing. True in
 *                  development so the service is usable from a cold cluster; something to think
 *                  about in production, where index creation is usually a deliberate act.
 */
@ConfigurationProperties(prefix = "aura.search")
public record SearchProperties(
    @DefaultValue("http://localhost:9200") String uri,
    @DefaultValue("products") String alias,
    @DefaultValue("true") boolean indexBootstrap
) {

    /**
     * The concrete index behind the alias for a given generation.
     *
     * <p>{@code products} → {@code products_v1}. The suffix is what a reindex increments.
     */
    public String indexName(int generation) {
        return alias + "_v" + generation;
    }
}
