package com.aura.catalog.media;

import com.aura.common.web.error.BusinessRuleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Asks media-service directly, forwarding the seller's own bearer token.
 *
 * <p>Forwarding rather than using a service account is the point, not a shortcut. media-service
 * scopes {@code GET /api/media/{id}} to the owner, so a request made as the seller answers two
 * questions in one call: is this file servable, and is it <em>theirs</em>. Catalog cannot answer
 * the second on its own — it has no record of who uploaded what — and without it a seller could
 * attach another seller's media id to their own product simply by guessing it.
 *
 * <p>The cost is that publishing depends on media-service being reachable. That is the correct
 * direction to fail: a product whose images cannot be confirmed servable should not go live. The
 * call happens only on writes that touch media, never on a storefront read path.
 *
 * <p>If this ever becomes a bottleneck, the replacement is a local projection fed by a
 * {@code MediaReady} event rather than a cache with its own staleness bugs — which is why callers
 * see {@link MediaGateway} and not this class.
 */
@Slf4j
@Component
public class HttpMediaGateway implements MediaGateway {

    private static final String READY = "READY";

    private final RestClient restClient;

    public HttpMediaGateway(RestClient.Builder builder, MediaGatewayProperties properties) {
        this.restClient = builder.baseUrl(properties.baseUrl()).build();
    }

    @Override
    public boolean isUsableBy(UUID mediaId) {
        try {
            MediaSummary summary = restClient.get()
                .uri("/api/media/{mediaId}", mediaId)
                .header(HttpHeaders.AUTHORIZATION, currentBearerToken())
                .retrieve()
                // A 404 means either "no such file" or "not yours" - media-service deliberately
                // does not distinguish them, and neither should this.
                .onStatus(status -> status.value() == 404, (request, response) -> { })
                .body(MediaSummary.class);

            return summary != null && READY.equals(summary.status());

        } catch (RestClientException e) {
            // Fail closed. Treating an unreachable media-service as "probably fine" is how a
            // product goes live with unscanned images attached.
            log.error("Could not reach media-service to check {}", mediaId, e);
            throw new BusinessRuleException("media-check-unavailable",
                "Could not verify the attached files right now. Please try again shortly.");
        }
    }

    @Override
    public void publish(UUID mediaId) {
        try {
            restClient.post()
                .uri("/api/media/{mediaId}/publish", mediaId)
                .header(HttpHeaders.AUTHORIZATION, currentBearerToken())
                .retrieve()
                .toBodilessEntity();

        } catch (RestClientException e) {
            // Fails the attach rather than being swallowed. Carrying on would produce a product
            // that looks correctly configured in the panel and renders with broken images on the
            // storefront - a defect nobody notices until a shopper does.
            log.error("Could not publish media {} for storefront display", mediaId, e);
            throw new BusinessRuleException("media-publish-failed",
                "Could not make the attached file publicly viewable. Please try again shortly.");
        }
    }

    private String currentBearerToken() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String header = servletAttributes.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && !header.isBlank()) {
                return header;
            }
        }
        // Reachable only if this is called outside a request, which would mean a background job
        // started referencing media - at which point it needs its own credentials, not a borrowed one.
        throw new IllegalStateException(
            "No bearer token on the current request; media checks run as the calling seller");
    }

    /** Only the fields catalog acts on. media-service's response carries more. */
    private record MediaSummary(UUID id, String status) {
    }
}
