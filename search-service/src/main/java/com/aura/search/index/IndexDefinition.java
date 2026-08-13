package com.aura.search.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The index settings and mappings, read from {@code elasticsearch/product-index.json}.
 *
 * <p>JSON on the classpath rather than assembled with the client's builders. The analysis chain is
 * the part of this service most likely to be wrong and most likely to need adjusting by someone
 * reading Elasticsearch's own documentation, and that documentation is written in JSON. A builder
 * translation of it is one more place for a transcription error nobody can see.
 */
@Slf4j
@Component
public class IndexDefinition {

    private static final String RESOURCE = "elasticsearch/product-index.json";

    private final String json;

    public IndexDefinition() {
        this.json = read();
    }

    public String json() {
        return json;
    }

    private String read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // The service cannot index or search without it; failing at startup is the honest
            // outcome, rather than starting and answering every query with nothing.
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }
}
