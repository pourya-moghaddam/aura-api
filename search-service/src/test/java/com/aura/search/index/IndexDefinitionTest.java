package com.aura.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The index definition as it leaves the classpath.
 *
 * <p>{@link PersianAnalysisIT} proves the chain <em>behaves</em>; this proves the file is present,
 * parseable and still contains the filters that behaviour depends on. The two fail differently: a
 * missing filter here is caught in milliseconds, without waiting for a container to start.
 */
class IndexDefinitionTest {

    private final JsonNode definition = parse();

    private JsonNode parse() {
        try {
            return new ObjectMapper().readTree(new IndexDefinition().json());
        } catch (Exception e) {
            throw new AssertionError("the index definition is not valid JSON", e);
        }
    }

    @Test
    @DisplayName("it is on the classpath and parses")
    void loads() {
        assertThat(definition.isObject()).isTrue();
    }

    @Test
    @DisplayName("the indexing analyzer carries every Persian filter, in order")
    void analysisChain() {
        // Order matters: normalisation has to run before the stop-word list, or the stop words are
        // compared against unnormalised tokens and simply do not match.
        JsonNode filters = definition
            .path("settings").path("analysis").path("analyzer").path("aura_persian").path("filter");

        assertThat(filters).hasSize(5);
        assertThat(filters.get(0).asText()).isEqualTo("lowercase");
        assertThat(filters.get(1).asText()).isEqualTo("decimal_digit");
        assertThat(filters.get(2).asText()).isEqualTo("arabic_normalization");
        assertThat(filters.get(3).asText()).isEqualTo("persian_normalization");
        assertThat(filters.get(4).asText()).isEqualTo("persian_stop_words");
    }

    @Test
    @DisplayName("the zero-width non-joiner char filter is applied")
    void zwnjCharFilter() {
        assertThat(definition.path("settings").path("analysis")
            .path("analyzer").path("aura_persian").path("char_filter").get(0).asText())
            .isEqualTo("zwnj_removal");

        assertThat(definition.path("settings").path("analysis")
            .path("char_filter").path("zwnj_removal").path("mappings").get(0).asText())
            .isEqualTo("\\u200C=>");
    }

    @Test
    @DisplayName("the mapping does not accept fields nobody declared")
    void dynamicMappingIsOff() {
        // dynamic: false, so a stray field in an event is stored but not indexed. The alternative
        // is Elasticsearch guessing a type from the first document it sees and being stuck with it.
        assertThat(definition.path("mappings").path("dynamic").asText()).isEqualTo("false");
    }

    @Test
    @DisplayName("attribute values are keywords, because the sidebar aggregates on them")
    void attributesAreKeywords() {
        assertThat(definition.path("mappings").path("dynamic_templates").get(0)
            .path("attribute_values_as_keywords").path("mapping").path("type").asText())
            .isEqualTo("keyword");
    }

    @Test
    @DisplayName("the definition is read once, not on every call")
    void cached() {
        IndexDefinition definition = new IndexDefinition();
        assertThat(definition.json()).isSameAs(definition.json());
    }
}
