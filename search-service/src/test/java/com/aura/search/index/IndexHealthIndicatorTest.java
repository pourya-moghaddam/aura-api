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
import org.springframework.boot.health.contributor.Status;

import java.io.IOException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Whether the thing this service reads through actually exists.
 *
 * <p>A cluster that is up and an index that is missing look identical to a plain connection check,
 * and the difference is the difference between working search and every query quietly returning
 * nothing. Since the bootstrapper deliberately does not crash on failure, this is what makes the
 * gap visible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexHealthIndicatorTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIndicesClient indices;

    private final SearchProperties properties =
        new SearchProperties("http://localhost:9200", "products", true);

    private IndexHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        when(client.indices()).thenReturn(indices);
        indicator = new IndexHealthIndicator(client, properties);
    }

    @Test
    @DisplayName("up when the alias is there")
    @SuppressWarnings("unchecked")
    void up() throws IOException {
        when(indices.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(true));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        assertThat(indicator.health().getDetails()).containsEntry("alias", "products");
    }

    @Test
    @DisplayName("down when the cluster is up but the alias is missing")
    @SuppressWarnings("unchecked")
    void downWhenAliasMissing() throws IOException {
        // The case a connection check cannot see, and the reason this indicator exists.
        when(indices.existsAlias(any(Function.class))).thenReturn(new BooleanResponse(false));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails())
            .containsEntry("reason", "the alias does not exist; nothing can be searched");
    }

    @Test
    @DisplayName("down when the cluster cannot be reached at all")
    @SuppressWarnings("unchecked")
    void downWhenUnreachable() throws IOException {
        when(indices.existsAlias(any(Function.class))).thenThrow(new IOException("no route to host"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
