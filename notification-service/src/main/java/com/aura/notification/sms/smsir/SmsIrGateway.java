package com.aura.notification.sms.smsir;

import com.aura.notification.config.SmsProperties;
import com.aura.notification.sms.SmsGateway;
import com.aura.notification.sms.SmsReceipt;
import com.aura.notification.sms.SmsRejectedException;
import com.aura.notification.sms.SmsUnavailableException;
import com.aura.notification.sms.smsir.dto.SmsIrBulkRequest;
import com.aura.notification.sms.smsir.dto.SmsIrBulkResponse;
import com.aura.notification.sms.smsir.dto.SmsIrVerifyParameter;
import com.aura.notification.sms.smsir.dto.SmsIrVerifyRequest;
import com.aura.notification.sms.smsir.dto.SmsIrVerifyResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * sms.ir.
 *
 * <p>The whole point of this class over its predecessor is that it never returns normally after a
 * failure. The old version wrapped everything in {@code catch (Exception)}, logged, and returned —
 * so the binder saw a clean consume, committed the offset, and the message was gone. A shopper
 * waiting for a login code got nothing and nothing was retried.
 *
 * <p>Failures are sorted into two kinds because they need opposite responses. A timeout or a 502 is
 * worth another go. A bad template id or an unusable number is not: it would be refused identically
 * on every retry while the partition stalls behind it.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.notification.sms.mode", havingValue = "http")
public class SmsIrGateway implements SmsGateway {

    private final RestClient restClient;
    private final SmsProperties.SmsIrProperties properties;

    public SmsIrGateway(RestClient restClient, SmsProperties smsProperties) {
        this.restClient = restClient;
        this.properties = smsProperties.smsIr();

        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            // Refusing to start beats starting and failing every send: the second looks like a
            // provider outage and sends someone hunting in the wrong place.
            throw new IllegalStateException(
                "aura.notification.sms.mode=http needs SMS_IR_API_KEY");
        }
    }

    @Override
    public SmsReceipt sendTemplate(String phone, int templateId, Map<String, String> parameters) {
        List<SmsIrVerifyParameter> body = parameters.entrySet().stream()
            .map(entry -> new SmsIrVerifyParameter(entry.getKey(), entry.getValue()))
            .toList();

        SmsIrVerifyResponse response = call(properties.verifyPath(),
            new SmsIrVerifyRequest(phone, templateId, body), SmsIrVerifyResponse.class);

        if (response == null || response.status() != 1 || response.data() == null) {
            throw new SmsRejectedException(describe(
                response == null ? null : response.status(),
                response == null ? "empty response" : response.message()));
        }

        return new SmsReceipt(String.valueOf(response.data().messageId()),
            BigDecimal.valueOf(response.data().cost()));
    }

    @Override
    public SmsReceipt sendText(String phone, String body) {
        SmsIrBulkResponse response = call(properties.bulkPath(),
            new SmsIrBulkRequest(properties.lineNumber(), body, List.of(phone)),
            SmsIrBulkResponse.class);

        if (response == null || response.status() != 1 || response.data() == null) {
            throw new SmsRejectedException(describe(
                response == null ? null : response.status(),
                response == null ? "empty response" : response.message()));
        }

        List<Long> ids = response.data().messageIds();
        return new SmsReceipt(
            ids == null || ids.isEmpty() ? String.valueOf(response.data().packId())
                : String.valueOf(ids.getFirst()),
            BigDecimal.valueOf(response.data().cost()));
    }

    private <T> T call(String path, Object body, Class<T> responseType) {
        try {
            return restClient.post()
                .uri(properties.baseUrl() + path)
                .header("x-api-key", properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(responseType);

        } catch (ResourceAccessException e) {
            // The connection never completed. Nothing was sent, so a retry cannot duplicate.
            throw new SmsUnavailableException("sms.ir could not be reached", e);

        } catch (HttpServerErrorException e) {
            throw new SmsUnavailableException("sms.ir returned " + e.getStatusCode(), e);

        } catch (HttpClientErrorException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 429) {
                // Rate limited is the one 4xx that becomes true again on its own.
                throw new SmsUnavailableException("sms.ir rate limited this account", e);
            }
            throw new SmsRejectedException("sms.ir refused the request: " + status
                + " " + e.getResponseBodyAsString());
        }
    }

    /**
     * The provider's own words, kept for the delivery log.
     *
     * <p>Deliberately not the request body: that carries the one-time code, and an error log is
     * exactly the place a code should never end up.
     */
    private String describe(Integer status, String message) {
        return "sms.ir status=" + status + " message=" + message;
    }
}
