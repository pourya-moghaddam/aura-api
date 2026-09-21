package com.aura.search.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The alias-and-generation naming that makes a mapping change survivable.
 */
class SearchPropertiesTest {

    private final SearchProperties properties =
        new SearchProperties("http://localhost:9200", null, null, "products", true);

    @Test
    @DisplayName("an index name is the alias with a generation suffix")
    void indexNaming() {
        // products -> products_v1. The suffix is what a reindex increments, and the alias is what
        // everything else addresses, so nothing outside the reindex ever learns the name changed.
        assertThat(properties.indexName(1)).isEqualTo("products_v1");
        assertThat(properties.indexName(2)).isEqualTo("products_v2");
    }

    @Test
    @DisplayName("a renamed alias carries its indices with it")
    void aliasDrivesTheIndexName() {
        assertThat(new SearchProperties("http://es:9200", null, null, "catalogue", true).indexName(3))
            .isEqualTo("catalogue_v3");
    }
}
