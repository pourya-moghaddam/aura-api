package com.aura.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * The Elasticsearch client, wired by hand.
 *
 * <p>The official typed client rather than Spring Data Elasticsearch. What this service does is
 * almost entirely query construction — analysis chains, post-filtered aggregations, external
 * versioning — and a repository abstraction hides exactly those, leaving you writing raw queries
 * through a layer that was meant to save you from them.
 *
 * <p>The application's own {@link ObjectMapper} is handed to the transport so documents serialise
 * the same way here as anywhere else in the service: two mappers means two answers to how a date
 * or a null is written, and the difference only shows up in the index.
 */
@Configuration
public class ElasticsearchConfig {

    /**
     * <p>Credentials are attached only when configured. Development runs Elasticsearch with
     * security off and has none; production turns it on, and a client that sends none gets 401 on
     * every call — including the index bootstrap, which fails at startup while the service still
     * reports healthy. Search would simply return nothing, forever, with no error anyone sees.
     */
    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(SearchProperties properties) {
        URI uri = URI.create(properties.uri());
        var builder = RestClient.builder(
            new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()));

        if (properties.isSecured()) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(
                properties.username(), properties.password()));
            builder.setHttpClientConfigCallback(
                http -> http.setDefaultCredentialsProvider(credentials));
        }

        return builder.build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient, ObjectMapper objectMapper) {
        return new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper)));
    }
}
