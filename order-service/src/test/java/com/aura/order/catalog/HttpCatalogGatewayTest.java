package com.aura.order.catalog;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.order.config.CatalogClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Where order-service learns what a variant costs and whether it exists.
 *
 * <p>Two behaviours here decide whether a shopper is charged correctly. The service key
 * authenticates the call, because a guest has no token of their own — the opposite of catalog's
 * media check, which forwards the seller's token precisely because ownership is the question. And
 * an unreachable catalog fails closed: guessing a price or a stock level is how someone is charged
 * the wrong amount or sold something that is not there.
 */
class HttpCatalogGatewayTest {

    private static final String BASE_URL = "http://catalog-service:8083";
    private static final String API_KEY = "test-internal-key";

    private MockRestServiceServer server;
    private HttpCatalogGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpCatalogGateway(builder, new CatalogClientProperties(BASE_URL, API_KEY));
    }

    private String body(long variantId, long price, int available, boolean purchasable) {
        return """
            [{"variantId":%d,"productId":3,"sellerId":9,"productName":"Shirt","productSlug":"shirt",
              "colorName":"Navy","sizeName":"L","unitPrice":%d,"purchasable":%s,"available":%d}]
            """.formatted(variantId, price, purchasable, available);
    }

    @Test
    @DisplayName("a snapshot comes back keyed by variant id")
    void returnsSnapshotsById() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                BASE_URL + "/api/internal/catalog/variants")))
            .andExpect(header("X-Internal-Api-Key", API_KEY))
            .andRespond(withSuccess(body(7L, 250_000L, 4, true), MediaType.APPLICATION_JSON));

        Map<Long, VariantSnapshot> snapshots = gateway.snapshotsFor(List.of(7L));

        assertThat(snapshots).containsKey(7L);
        assertThat(snapshots.get(7L).unitPrice()).isEqualTo(250_000L);
        assertThat(snapshots.get(7L).available()).isEqualTo(4);
        assertThat(snapshots.get(7L).purchasable()).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("the service key authenticates the call, not a shopper's token")
    void usesTheServiceKey() {
        // A guest has no token at all - requirement 12 - and "what does this cost" does not
        // depend on who is asking.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
            .andExpect(header("X-Internal-Api-Key", API_KEY))
            .andRespond(withSuccess(body(7L, 1L, 1, true), MediaType.APPLICATION_JSON));

        gateway.snapshotsFor(List.of(7L));
        server.verify();
    }

    @Test
    @DisplayName("ids are passed as query parameters, deduplicated")
    void deduplicatesIds() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
            .andExpect(queryParam("ids", "7"))
            .andRespond(withSuccess(body(7L, 1L, 1, true), MediaType.APPLICATION_JSON));

        gateway.snapshotsFor(List.of(7L, 7L, 7L));
        server.verify();
    }

    @Test
    @DisplayName("an unreachable catalog fails closed rather than assuming anything")
    void failsClosed() {
        // Treating an outage as "probably still £10 and in stock" is how a shopper is charged the
        // wrong amount, or sold the last one twice.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
            .andRespond(withServerError());

        assertThatThrownBy(() -> gateway.snapshotsFor(List.of(7L)))
            .isInstanceOf(BusinessRuleException.class)
            .hasMessageContaining("not available right now");
    }

    @Test
    @DisplayName("an empty request does not call catalog at all")
    void emptyRequestShortCircuits() {
        assertThat(gateway.snapshotsFor(List.of())).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("a variant catalog no longer knows about is simply absent")
    void unknownVariantIsAbsent() {
        // How the caller learns a variant was deleted rather than merely withdrawn - the cart
        // shows the line as unbuyable instead of failing the whole page.
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(gateway.snapshotsFor(List.of(999L))).doesNotContainKey(999L);
    }
}
