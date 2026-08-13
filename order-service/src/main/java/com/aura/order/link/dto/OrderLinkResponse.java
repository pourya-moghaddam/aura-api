package com.aura.order.link.dto;

import com.aura.order.link.SellerOrderLink;

import java.time.OffsetDateTime;

/**
 * A link as the seller sees it.
 *
 * @param url        the whole address to send the buyer. Present only on creation — it contains
 *                   the raw token, which is never stored and therefore can never be shown again.
 *                   A seller who loses it composes a new order.
 * @param traceCode  so the seller can find the order afterwards without the token
 */
public record OrderLinkResponse(
    Long orderId,
    String traceCode,
    String url,
    Long total,
    OffsetDateTime expiresAt,
    OffsetDateTime usedAt
) {

    public static OrderLinkResponse created(SellerOrderLink link, String traceCode, long total,
                                            String url) {
        return new OrderLinkResponse(link.getOrderId(), traceCode, url, total,
            link.getExpiresAt(), link.getUsedAt());
    }

    /** Without the URL: the token is gone, and only its fate can be reported. */
    public static OrderLinkResponse existing(SellerOrderLink link, String traceCode, long total) {
        return new OrderLinkResponse(link.getOrderId(), traceCode, null, total,
            link.getExpiresAt(), link.getUsedAt());
    }
}
