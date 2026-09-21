package com.aura.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.search.index.IndexBootstrapper;
import com.aura.search.index.IndexDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Talking to an Elasticsearch that wants credentials.
 *
 * <p>Every other test in this service runs against a cluster with security switched off, which is
 * how production ran for a while without anyone noticing that the client could not authenticate at
 * all: compose.prod.yaml sets {@code xpack.security.enabled=true}, and nothing read a username.
 * The service would have started, reported healthy, and returned no search results ever.
 */
@Testcontainers
class SecuredElasticsearchIT {

    private static final String PASSWORD = "aura-test-secret";

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "true")
        // TLS off, password on. This test is about credentials reaching the server, and a
        // self-signed certificate would only add a second reason for it to fail.
        .withEnv("xpack.security.http.ssl.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ELASTIC_PASSWORD", PASSWORD)
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private SearchProperties properties(String username, String password) {
        return new SearchProperties("http://" + ELASTICSEARCH.getHttpHostAddress(),
            username, password, "products", true);
    }

    private ElasticsearchClient clientFor(SearchProperties properties) {
        RestClient rest = new ElasticsearchConfig().elasticsearchRestClient(properties);
        return new ElasticsearchClient(
            new RestClientTransport(rest, new JacksonJsonpMapper(
                new ObjectMapper().findAndRegisterModules())));
    }

    @Test
    @DisplayName("credentials are configured only when a username is set")
    void securedIsDrivenByTheUsername() {
        assertThat(properties("elastic", PASSWORD).isSecured()).isTrue();
        // Development leaves both blank, and the client must then send nothing rather than
        // an empty username the server would reject.
        assertThat(properties(null, null).isSecured()).isFalse();
        assertThat(properties("  ", PASSWORD).isSecured()).isFalse();
    }

    @Test
    @DisplayName("without credentials the cluster refuses everything")
    void unauthenticatedIsRefused() {
        // The state production was in. Note what it looks like: not a startup crash, just an
        // exception on the first call - which the index bootstrap logs and carries on from.
        // The typed client wraps the 401 rather than surfacing the status code, so match on what
        // the server actually said.
        assertThatThrownBy(() -> clientFor(properties(null, null)).info())
            .isInstanceOf(ElasticsearchException.class)
            .hasMessageContaining("missing authentication credentials");
    }

    @Test
    @DisplayName("with credentials the index can be created and queried")
    void authenticatedWorks() throws Exception {
        ElasticsearchClient client = clientFor(properties("elastic", PASSWORD));

        assertThat(client.info().version().number()).startsWith("8.");

        // The operation that actually matters at startup: if this cannot run, the alias never
        // exists and every search returns nothing for the life of the deployment.
        new IndexBootstrapper(client, new IndexDefinition(), properties("elastic", PASSWORD))
            .createIndexIfMissing();

        assertThat(client.indices().exists(e -> e.index("products")).value()).isTrue();
    }

    @Test
    @DisplayName("a wrong password is refused rather than silently ignored")
    void wrongPasswordIsRefused() {
        assertThatThrownBy(() -> clientFor(properties("elastic", "not-the-password")).info())
            .isInstanceOf(ElasticsearchException.class)
            .hasMessageContaining("security_exception");
    }
}
