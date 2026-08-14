package com.aura.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.aura.search.config.SearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the index is created, and — more interestingly — when it is not.
 *
 * <p>Two of the three behaviours here are refusals to act, and both are deliberate: re-applying
 * settings to a live index cannot work for analysis changes, and an Elasticsearch that is merely
 * slow to start must not crash-loop the service.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexBootstrapperTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIndicesClient indices;

    private IndexBootstrapper bootstrapper(boolean bootstrapEnabled) {
        return new IndexBootstrapper(client, new IndexDefinition(),
            new SearchProperties("http://localhost:9200", null, null, "products", bootstrapEnabled));
    }

    @BeforeEach
    void setUp() {
        when(client.indices()).thenReturn(indices);
    }

    @SuppressWarnings("unchecked")
    private void aliasExists(boolean exists) throws IOException {
        when(indices.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(exists));
    }

    @Test
    @DisplayName("creates the index when the alias is missing")
    void createsWhenMissing() throws IOException {
        aliasExists(false);

        bootstrapper(true).createIndexIfMissing();

        verify(indices).create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class));
    }

    @Test
    @DisplayName("does nothing when the alias already exists")
    void skipsWhenPresent() throws IOException {
        // Re-applying settings to a live index is not possible for analysis changes - Elasticsearch
        // refuses them on an open index - so trying would turn a startup into a confusing failure.
        // A mapping change is a reindex, which is its own deliberate operation.
        aliasExists(true);

        bootstrapper(true).createIndexIfMissing();

        verify(indices, never()).create(any(co.elastic.clients.elasticsearch.indices.CreateIndexRequest.class));
    }

    @Test
    @DisplayName("does nothing at all when bootstrap is switched off")
    void respectsTheSwitch() throws IOException {
        // Production usually wants index creation to be a deliberate act rather than a side effect
        // of a deployment.
        bootstrapper(false).createIndexIfMissing();

        verify(client, never()).indices();
    }

    @Test
    @DisplayName("an unreachable Elasticsearch is logged, not fatal")
    @SuppressWarnings("unchecked")
    void doesNotCrashTheService() throws IOException {
        // Elasticsearch may simply be slower to start than this service, and a crash-loop helps
        // nobody. The health indicator reports the gap and search fails loudly per request until
        // it closes.
        when(indices.existsAlias(any(Function.class))).thenThrow(new IOException("connection refused"));

        assertThatCode(() -> bootstrapper(true).createIndexIfMissing()).doesNotThrowAnyException();
    }
}
