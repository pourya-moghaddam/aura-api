package com.aura.order.payment.zarinpal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The development gateway.
 *
 * <p>Worth testing for one reason: it has to reproduce the 100-then-101 behaviour, because that is
 * what the local end-to-end runs exercise. A mock that returned 100 every time would let an
 * idempotency bug through every test that uses it.
 */
class MockZarinpalClientTest {

    private final MockZarinpalClient client = new MockZarinpalClient();

    private String authority() {
        return client.request(1000L, "d", "http://cb", null, null).authority();
    }

    @Test
    @DisplayName("a request succeeds and issues an S-prefixed authority")
    void issuesSandboxShapedAuthority() {
        ZarinpalClient.RequestResult result = client.request(1000L, "d", "http://cb", null, null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.authority()).startsWith("S").hasSize(36);
    }

    @Test
    @DisplayName("the first verify is 100 and every one after it is 101")
    void secondVerifyIsAlreadyVerified() {
        String authority = authority();

        ZarinpalClient.VerifyResult first = client.verify(1000L, authority);
        ZarinpalClient.VerifyResult second = client.verify(1000L, authority);
        ZarinpalClient.VerifyResult third = client.verify(1000L, authority);

        assertThat(first.code()).isEqualTo(100);
        assertThat(second.code()).isEqualTo(101);
        assertThat(third.code()).isEqualTo(101);
        assertThat(first.isPaid()).isTrue();
        assertThat(second.isPaid()).isTrue();
    }

    @Test
    @DisplayName("the reference number does not change between verifies")
    void refIdIsStable() {
        // A real reference number identifies the transaction, so it cannot be reinvented on each
        // call - a customer quoting it to support has to find the same payment.
        String authority = authority();

        assertThat(client.verify(1000L, authority).refId())
            .isEqualTo(client.verify(1000L, authority).refId());
    }

    @Test
    @DisplayName("an authority it never issued is refused")
    void unknownAuthority() {
        ZarinpalClient.VerifyResult result = client.verify(1000L, "S-never-issued");

        assertThat(result.isPaid()).isFalse();
        assertThat(result.code()).isEqualTo(-51);
    }

    @Test
    @DisplayName("two authorities are independent")
    void authoritiesAreIndependent() {
        String one = authority();
        String two = authority();

        assertThat(one).isNotEqualTo(two);
        client.verify(1000L, one);
        assertThat(client.verify(1000L, two).code()).isEqualTo(100);
    }
}
