package com.aura.order.payment.zarinpal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param merchantId  the 36-character code Zarinpal issues per gateway. In sandbox any UUID will
 *                    do; in production this is a secret and belongs in the environment.
 * @param baseUrl     {@code https://payment.zarinpal.com} live, {@code https://sandbox.zarinpal.com}
 *                    for testing. Sandbox authorities begin with S rather than A.
 * @param callbackUrl where Zarinpal returns the shopper. Must be reachable from their browser, so
 *                    it is the public gateway address rather than this service's.
 * @param resultUrl   the storefront page the callback finally redirects to. The trace code and
 *                    outcome are appended.
 * @param mode        {@code http} talks to Zarinpal; {@code mock} settles locally. Mock exists so
 *                    the whole flow can be exercised without a merchant account — it must never be
 *                    set in production, where it would mark every order paid for nothing.
 */
@ConfigurationProperties(prefix = "aura.payment.zarinpal")
public record ZarinpalProperties(
    @DefaultValue("") String merchantId,
    @DefaultValue("https://sandbox.zarinpal.com") String baseUrl,
    @DefaultValue("http://localhost:8080/api/orders/payments/callback") String callbackUrl,
    @DefaultValue("http://localhost:3000/checkout/result") String resultUrl,
    @DefaultValue("mock") String mode
) {

    /** Where the shopper is sent to type their card details. */
    public String startPayUrl(String authority) {
        return baseUrl + "/pg/StartPay/" + authority;
    }

    public boolean isMock() {
        return "mock".equalsIgnoreCase(mode);
    }
}
