package com.aura.order.catalog;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.order.config.CatalogClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calls catalog's internal variant endpoint with the shared service key.
 *
 * <p>A service key rather than the shopper's token, because a guest has no token — requirement 12
 * — and because "what does this variant cost" does not depend on who is asking. That is the
 * opposite of the media check in catalog, which forwards the seller's token precisely because
 * ownership is the question being asked.
 *
 * <p>Fails closed. An unreachable catalog means the price and stock are unknown, and guessing
 * either is how a shopper is charged the wrong amount or sold something that is not there.
 */
@Slf4j
@Component
public class HttpCatalogGateway implements CatalogGateway {

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final String apiKey;

    public HttpCatalogGateway(RestClient.Builder builder, CatalogClientProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.apiKey = properties.internalApiKey();
    }

    @Override
    public Map<Long, VariantSnapshot> snapshotsFor(Collection<Long> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }

        try {
            List<VariantSnapshot> snapshots = restClient.get()
                .uri(uri -> uri.path("/api/internal/catalog/variants")
                    .queryParam("ids", variantIds.stream().distinct().toList())
                    .build())
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .body(new ParameterizedTypeReference<List<VariantSnapshot>>() { });

            return snapshots == null ? Map.of() : snapshots.stream()
                .collect(Collectors.toMap(VariantSnapshot::variantId, Function.identity()));

        } catch (RestClientException e) {
            log.error("Could not reach catalog for variants {}", variantIds, e);
            throw new BusinessRuleException("catalog-unavailable",
                "Product information is not available right now. Please try again shortly.");
        }
    }
}
