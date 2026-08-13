package com.aura.order.payment.zarinpal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A gateway that always says yes, for development.
 *
 * <p>It exists so the whole payment path — request, redirect, callback, verify, settlement — can
 * be exercised without a merchant account, and so the reconciliation sweep has something to
 * reconcile. It mimics the one behaviour that matters most: the second verify of a payment returns
 * 101 rather than 100, because reading 101 as a failure is the classic way to cancel an order
 * that was paid for.
 *
 * <p>Selected by {@code aura.payment.zarinpal.mode=mock}, which is the default and which
 * <strong>must not</strong> be left in place in production. It logs loudly for that reason.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "aura.payment.zarinpal.mode", havingValue = "mock",
    matchIfMissing = true)
public class MockZarinpalClient implements ZarinpalClient {

    private final Map<String, Boolean> verified = new ConcurrentHashMap<>();

    public MockZarinpalClient() {
        log.warn("Zarinpal is in MOCK mode: every payment will be treated as successful. "
            + "Set aura.payment.zarinpal.mode=http with a real merchant id before taking money.");
    }

    @Override
    public RequestResult request(long amount, String description, String callbackUrl,
                                 String mobile, String orderId) {
        // Sandbox authorities begin with S; the mock follows suit so nothing downstream can come
        // to depend on the live A prefix.
        // 36 characters, S-prefixed, as Zarinpal's sandbox issues them.
        String authority = "S" + UUID.randomUUID().toString().replace("-", "") + "000";
        verified.put(authority, false);

        log.info("Mock gateway issued authority {} for {} Rial", authority, amount);
        return new RequestResult(100, authority, "Success",
            "{\"mock\":true,\"amount\":" + amount + "}");
    }

    @Override
    public VerifyResult verify(long amount, String authority) {
        Boolean alreadyVerified = verified.get(authority);
        if (alreadyVerified == null) {
            // An authority this instance never issued - a restart, or a hand-typed callback.
            return new VerifyResult(-51, null, null, "Unknown authority", "{\"mock\":true}");
        }

        if (alreadyVerified) {
            return new VerifyResult(101, refIdFor(authority), "502229******5995",
                "Verified", "{\"mock\":true,\"repeat\":true}");
        }

        verified.put(authority, true);
        return new VerifyResult(100, refIdFor(authority), "502229******5995",
            "Verified", "{\"mock\":true}");
    }

    /** Stable across repeat verifies, as a real reference number is. */
    private String refIdFor(String authority) {
        return String.valueOf(Math.abs(authority.hashCode()) % 1_000_000_000);
    }
}
