package com.aura.search.trending;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.aura.common.events.OrderPaidEvent;
import com.aura.common.events.ProductChangedEvent;
import com.aura.search.config.SearchProperties;
import com.aura.search.index.IndexBootstrapper;
import com.aura.search.index.IndexDefinition;
import com.aura.search.index.ProductIndexer;
import com.aura.search.query.dto.SearchHit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.testcontainers.RedisContainer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trending, against a real Redis and a real Elasticsearch.
 *
 * <p>Both are needed because the whole point of the design is that the two stores are separate:
 * Redis holds how much sold, the index holds what the product is, and the interesting failures are
 * at the seam — a redelivered event counted twice, or a best seller that has since been withdrawn
 * still shown on the homepage.
 */
@Testcontainers
class TrendingIT {

    @Container
    static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
        DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.3"))
        .withEnv("xpack.security.enabled", "false")
        .withEnv("discovery.type", "single-node")
        .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    @Container
    static final RedisContainer REDIS =
        new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    private static final long SHIRTS = 2L;

    private static StringRedisTemplate redis;
    private static SalesCounter salesCounter;
    private static TrendingService trending;

    @BeforeAll
    static void indexACatalogue() throws IOException {
        URI uri = URI.create("http://" + ELASTICSEARCH.getHttpHostAddress());
        ElasticsearchClient client = new ElasticsearchClient(new RestClientTransport(
            RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), "http")).build(),
            new JacksonJsonpMapper(new ObjectMapper().findAndRegisterModules())));

        SearchProperties properties = new SearchProperties(uri.toString(), null, null, "products", true);
        new IndexBootstrapper(client, new IndexDefinition(), properties).createIndexIfMissing();

        ProductIndexer indexer = new ProductIndexer(client, properties);
        indexer.apply(product(1, "پیراهن آبی", "ACTIVE"));
        indexer.apply(product(2, "پیراهن مشکی", "ACTIVE"));
        indexer.apply(product(3, "کفش چرم", "ACTIVE"));
        // Sold well, then withdrawn. The case that separates "top of the sorted set" from
        // "something a shopper can actually buy".
        indexer.apply(product(4, "کیف قدیمی", "INACTIVE"));
        client.indices().refresh(r -> r.index("products"));

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
            REDIS.getRedisHost(), REDIS.getRedisPort());
        LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();

        redis = new StringRedisTemplate(factory);
        salesCounter = new SalesCounter(redis);
        trending = new TrendingService(salesCounter, client, properties);
    }

    @BeforeEach
    void clearTheCounts() {
        RedisConnectionFactory factory = redis.getRequiredConnectionFactory();
        factory.getConnection().serverCommands().flushDb();
    }

    @Test
    @DisplayName("counts quantities, not orders")
    void countsQuantities() {
        salesCounter.record(order(101, line(1, 7)));
        salesCounter.record(order(102, line(2, 1), line(2, 1)));

        // One order of seven beats two of one. Counting orders would rank them the other way and
        // make a bulk-bought product look unpopular.
        assertThat(salesCounter.salesOf(1)).isEqualTo(7);
        assertThat(salesCounter.salesOf(2)).isEqualTo(2);
    }

    @Test
    @DisplayName("a redelivered event does not count twice")
    void redeliveryIsIgnored() {
        OrderPaidEvent event = order(103, line(1, 5));

        salesCounter.record(event);
        salesCounter.record(event);
        salesCounter.record(event);

        // Kafka delivers at least once, and a consumer restarting mid-batch replays. Unlike the
        // index there is no version here to make a repeat harmless: an increment applied three
        // times is simply a lie about demand, and nothing downstream would ever notice.
        assertThat(salesCounter.salesOf(1)).isEqualTo(5);
    }

    @Test
    @DisplayName("two different orders for the same product do add up")
    void differentOrdersAccumulate() {
        salesCounter.record(order(104, line(1, 2)));
        salesCounter.record(order(105, line(1, 3)));

        // The mirror of the test above: deduplication must key on the event, not the product.
        assertThat(salesCounter.salesOf(1)).isEqualTo(5);
    }

    @Test
    @DisplayName("best sellers come back in sales order, not index order")
    void ordersBySales() {
        salesCounter.record(order(106, line(3, 2)));
        salesCounter.record(order(107, line(1, 9)));
        salesCounter.record(order(108, line(2, 5)));

        assertThat(trending.trending(10))
            .extracting(SearchHit::productId)
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("a withdrawn product is not trending, whatever it once sold")
    void withdrawnProductsAreExcluded() {
        salesCounter.record(order(109, line(4, 100)));
        salesCounter.record(order(110, line(1, 1)));

        // Product 4 outsold everything and is the top of the sorted set. Showing it would put a
        // dead link at the head of the homepage.
        assertThat(trending.trending(10))
            .extracting(SearchHit::productId)
            .containsExactly(1L);
    }

    @Test
    @DisplayName("the limit is filled from the next best sellers when one is withdrawn")
    void limitIsFilledPastGaps() {
        salesCounter.record(order(111, line(4, 100)));
        salesCounter.record(order(112, line(1, 9)));
        salesCounter.record(order(113, line(2, 5)));

        // Asking for two and over-fetching is what makes this two rather than one: the withdrawn
        // product occupies a rank but not a slot.
        assertThat(trending.trending(2))
            .extracting(SearchHit::productId)
            .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("nothing sold yet is an empty list, not an error")
    void emptyBeforeAnySales() {
        // A brand new shop. The homepage still has to render.
        assertThat(trending.trending(10)).isEmpty();
    }

    @Test
    @DisplayName("a product that sold but was never indexed is skipped")
    void unknownProductsAreSkipped() {
        salesCounter.record(order(114, line(999, 50)));
        salesCounter.record(order(115, line(1, 1)));

        assertThat(trending.trending(10))
            .extracting(SearchHit::productId)
            .containsExactly(1L);
    }

    private static OrderPaidEvent order(long orderId, OrderPaidEvent.Line... lines) {
        return new OrderPaidEvent(UUID.randomUUID(), Instant.now(), orderId,
            "TRACE" + orderId, List.of(lines));
    }

    private static OrderPaidEvent.Line line(long productId, int quantity) {
        return new OrderPaidEvent.Line(productId, productId * 10, quantity);
    }

    private static ProductChangedEvent product(long id, String name, String status) {
        return new ProductChangedEvent(
            UUID.randomUUID(), Instant.now(), id, 9L, SHIRTS, List.of(1L, SHIRTS),
            List.of("پوشاک"), name, "p" + id, "توضیحات", status, 100_000L, 100_000L, null, 5,
            Map.of(), List.of("Navy"), List.of("L"), null,
            Instant.parse("2026-01-01T00:00:00Z"), 1_000L + id, false);
    }
}
