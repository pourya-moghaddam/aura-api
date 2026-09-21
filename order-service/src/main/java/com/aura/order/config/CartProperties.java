package com.aura.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param guestRetention how long an untouched guest cart is kept. Generous, because a cart is
 *                       business data and a shopper returning a fortnight later expecting their
 *                       basket is ordinary rather than exceptional.
 * @param cookieSecure   whether the cart cookie carries {@code Secure}. Must be true in
 *                       production; false by default so local development over plain HTTP works —
 *                       browsers drop Secure cookies on http origins silently, which would look
 *                       like carts simply not persisting.
 */
@ConfigurationProperties(prefix = "aura.order.cart")
public record CartProperties(
    @DefaultValue("P30D") Duration guestRetention,
    @DefaultValue("false") boolean cookieSecure
) {
}
