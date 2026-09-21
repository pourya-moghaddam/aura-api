package com.aura.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param uri       where Elasticsearch is.
 * @param username  basic-auth user, when the cluster has security on. Blank in development, where
 *                  Elasticsearch runs with {@code xpack.security.enabled=false}; required in
 *                  production, where compose.prod.yaml turns it on. Without this the client sends
 *                  no credentials and every call comes back 401 — including the index bootstrap at
 *                  startup, so the service comes up healthy and search is simply dead.
 * @param password  the matching password.
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
    String username,
    String password,
    @DefaultValue("products") String alias,
    @DefaultValue("true") boolean indexBootstrap
) {

    /** Whether the cluster expects credentials. */
    public boolean isSecured() {
        return username != null && !username.isBlank();
    }

    /**
     * The concrete index behind the alias for a given generation.
     *
     * <p>{@code products} → {@code products_v1}. The suffix is what a reindex increments.
     */
    public String indexName(int generation) {
        return alias + "_v" + generation;
    }
}
