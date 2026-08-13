package com.aura.order.payment.zarinpal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Zarinpal over HTTP.
 *
 * <p>Errors come back in two shapes: a {@code data} object with a {@code code}, or an
 * {@code errors} array or object carrying one. Both are read, because which one appears depends on
 * the failure and treating an unparsed error as a success would be catastrophic in exactly one
 * direction.
 *
 * <p>Amounts are Rial, matching how money is stored throughout, so {@code currency} is IRR. Sending
 * a toman figure against an IRR gateway undercharges by a factor of ten and looks like nothing is
 * wrong until the settlement report.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.payment.zarinpal.mode", havingValue = "http")
public class HttpZarinpalClient implements ZarinpalClient {

    private final RestClient restClient;
    private final ZarinpalProperties properties;
    private final ObjectMapper objectMapper;

    public HttpZarinpalClient(RestClient.Builder builder, ZarinpalProperties properties,
                              ObjectMapper objectMapper) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
        this.properties = properties;
        this.objectMapper = objectMapper;

        if (properties.merchantId().isBlank()) {
            throw new IllegalStateException(
                "aura.payment.zarinpal.merchant-id must be set when the gateway mode is http");
        }
    }

    @Override
    public RequestResult request(long amount, String description, String callbackUrl,
                                 String mobile, String orderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchant_id", properties.merchantId());
        body.put("amount", amount);
        body.put("currency", "IRR");
        body.put("description", description);
        body.put("callback_url", callbackUrl);

        Map<String, String> metadata = new HashMap<>();
        if (mobile != null) {
            metadata.put("mobile", mobile);
        }
        if (orderId != null) {
            metadata.put("order_id", orderId);
        }
        if (!metadata.isEmpty()) {
            body.put("metadata", metadata);
        }

        String raw = post("/pg/v4/payment/request.json", body);
        JsonNode response = parse(raw);
        JsonNode data = response.path("data");

        return new RequestResult(codeOf(response), data.path("authority").asText(null),
            data.path("message").asText(null), raw);
    }

    @Override
    public VerifyResult verify(long amount, String authority) {
        // The amount is ours, not the caller's: Zarinpal checks it against what was requested, and
        // taking it from the request would let a tampered callback verify a different sum.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchant_id", properties.merchantId());
        body.put("amount", amount);
        body.put("authority", authority);

        String raw = post("/pg/v4/payment/verify.json", body);
        JsonNode response = parse(raw);
        JsonNode data = response.path("data");

        return new VerifyResult(codeOf(response),
            data.path("ref_id").isMissingNode() ? null : data.path("ref_id").asText(),
            data.path("card_pan").asText(null),
            data.path("message").asText(null), raw);
    }

    private String post(String path, Map<String, Object> body) {
        try {
            return restClient.post()
                .uri(path)
                .header("Accept", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

        } catch (RestClientException e) {
            // Deliberately not swallowed. An unreachable gateway during verify must leave the
            // payment pending so reconciliation asks again; pretending it failed would cancel an
            // order that may well have been paid for.
            log.error("Zarinpal call to {} failed", path, e);
            throw new ZarinpalUnavailableException("The payment gateway is not responding.", e);
        }
    }

    /**
     * The status code, from wherever this particular response put it.
     *
     * <p>{@code errors} is an array when empty and an object when populated, which no amount of
     * wishing makes consistent.
     */
    private int codeOf(JsonNode response) {
        JsonNode data = response.path("data");
        if (data.hasNonNull("code")) {
            return data.path("code").asInt();
        }

        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            return errors.get(0).path("code").asInt(0);
        }
        if (errors.isObject() && errors.hasNonNull("code")) {
            return errors.path("code").asInt();
        }
        return 0;
    }

    private JsonNode parse(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.error("Zarinpal returned something that is not JSON: {}", raw);
            throw new ZarinpalUnavailableException("The payment gateway returned a bad response", e);
        }
    }
}
