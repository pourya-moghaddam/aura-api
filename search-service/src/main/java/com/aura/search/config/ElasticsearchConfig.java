package com.aura.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpHost;
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

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(SearchProperties properties) {
        URI uri = URI.create(properties.uri());
        return RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
            .build();
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(RestClient restClient, ObjectMapper objectMapper) {
        return new ElasticsearchClient(
            new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper)));
    }
}
