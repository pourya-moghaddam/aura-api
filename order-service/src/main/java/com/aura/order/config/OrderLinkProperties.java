package com.aura.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param validity how long a seller's order link works for. Short, because the stock behind it is
 *                 held from the moment the order is written — a link with a month's life would
 *                 keep goods off the shelf for a buyer who has probably stopped answering.
 * @param baseUrl  the storefront address the link points at. The buyer opens it in a browser, so
 *                 it is the public site rather than this service.
 */
@ConfigurationProperties(prefix = "aura.order.link")
public record OrderLinkProperties(
    @DefaultValue("PT48H") Duration validity,
    @DefaultValue("http://localhost:3000/pay") String baseUrl
) {

    public String urlFor(String rawToken) {
        return baseUrl + "/" + rawToken;
    }
}
