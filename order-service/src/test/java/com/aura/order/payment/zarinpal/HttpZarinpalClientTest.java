package com.aura.order.payment.zarinpal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reading Zarinpal's answers.
 *
 * <p>Every case here is one where misreading the response costs real money in one direction or the
 * other: a failure read as a success gives goods away, and 101 read as a failure cancels an order
 * that was paid for.
 */
class HttpZarinpalClientTest {

    private static final String BASE = "https://sandbox.zarinpal.com";
    private static final String MERCHANT = "11111111-1111-1111-1111-111111111111";

    private MockRestServiceServer server;
    private HttpZarinpalClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpZarinpalClient(builder,
            new ZarinpalProperties(MERCHANT, BASE, "http://cb", "http://result", "http"),
            new ObjectMapper());
    }

    @Test
    @DisplayName("a successful request yields an authority")
    void requestSuccess() {
        server.expect(requestTo(BASE + "/pg/v4/payment/request.json"))
            .andExpect(jsonPath("$.merchant_id").value(MERCHANT))
            .andExpect(jsonPath("$.amount").value(1_040_000))
            // Money is stored in Rial throughout. Sending a toman figure against an IRR gateway
            // undercharges by a factor of ten and looks fine until the settlement report.
            .andExpect(jsonPath("$.currency").value("IRR"))
            .andExpect(jsonPath("$.callback_url").value("http://cb"))
            .andRespond(withSuccess("""
                {"data":{"code":100,"message":"Success","authority":"A0000001","fee":100},
                 "errors":[]}
                """, MediaType.APPLICATION_JSON));

        ZarinpalClient.RequestResult result =
            client.request(1_040_000L, "Aura order X", "http://cb", "+989121234567", "ABC");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.authority()).isEqualTo("A0000001");
    }

    @Test
    @DisplayName("the buyer's phone and order number go in the metadata")
    void requestCarriesMetadata() {
        server.expect(requestTo(BASE + "/pg/v4/payment/request.json"))
            .andExpect(jsonPath("$.metadata.mobile").value("+989121234567"))
            .andExpect(jsonPath("$.metadata.order_id").value("ABC"))
            .andRespond(withSuccess("""
                {"data":{"code":100,"authority":"A1"},"errors":[]}""",
                MediaType.APPLICATION_JSON));

        client.request(1000L, "d", "http://cb", "+989121234567", "ABC");
        server.verify();
    }

    @Test
    @DisplayName("an error carried in the errors object is read as a failure")
    void requestFailureFromErrorsObject() {
        // Zarinpal puts the code in data on success and in errors on failure. Failing to read the
        // second shape would leave a refused request looking like a success with no authority.
        server.expect(requestTo(BASE + "/pg/v4/payment/request.json"))
            .andRespond(withSuccess("""
                {"data":[],"errors":{"code":-9,"message":"Validation error","validations":[]}}
                """, MediaType.APPLICATION_JSON));

        ZarinpalClient.RequestResult result =
            client.request(1000L, "d", "http://cb", null, null);

        assertThat(result.code()).isEqualTo(-9);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a success code with no authority is still not a success")
    void requestWithoutAuthority() {
        server.expect(requestTo(BASE + "/pg/v4/payment/request.json"))
            .andRespond(withSuccess("{\"data\":{\"code\":100},\"errors\":[]}",
                MediaType.APPLICATION_JSON));

        assertThat(client.request(1000L, "d", "http://cb", null, null).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("verify quotes our amount, not the caller's")
    void verifySendsOurAmount() {
        // Zarinpal checks the amount against what was requested. Taking it from the callback would
        // let a tampered query string verify a different sum.
        server.expect(requestTo(BASE + "/pg/v4/payment/verify.json"))
            .andExpect(jsonPath("$.amount").value(1_040_000))
            .andExpect(jsonPath("$.authority").value("A0000001"))
            .andRespond(withSuccess("""
                {"data":{"code":100,"ref_id":201,"card_pan":"502229******5995"},"errors":[]}
                """, MediaType.APPLICATION_JSON));

        ZarinpalClient.VerifyResult result = client.verify(1_040_000L, "A0000001");

        assertThat(result.isPaid()).isTrue();
        assertThat(result.refId()).isEqualTo("201");
        assertThat(result.cardPan()).isEqualTo("502229******5995");
        server.verify();
    }

    @Test
    @DisplayName("101 is a paid transaction, not an error")
    void verifyAlreadyVerified() {
        // The whole idempotency story. Every verify after the first returns 101; reading it as a
        // failure cancels an order that was paid for and releases the stock underneath it.
        server.expect(requestTo(BASE + "/pg/v4/payment/verify.json"))
            .andRespond(withSuccess("""
                {"data":{"code":101,"message":"Verified","ref_id":201},"errors":[]}
                """, MediaType.APPLICATION_JSON));

        ZarinpalClient.VerifyResult result = client.verify(1000L, "A1");

        assertThat(result.isPaid()).isTrue();
        assertThat(result.isAlreadyVerified()).isTrue();
        assertThat(result.refId()).isEqualTo("201");
    }

    @Test
    @DisplayName("a refused verification is not paid")
    void verifyFailure() {
        server.expect(requestTo(BASE + "/pg/v4/payment/verify.json"))
            .andRespond(withSuccess("""
                {"data":[],"errors":{"code":-51,"message":"Session is not valid"}}
                """, MediaType.APPLICATION_JSON));

        ZarinpalClient.VerifyResult result = client.verify(1000L, "A1");

        assertThat(result.code()).isEqualTo(-51);
        assertThat(result.isPaid()).isFalse();
        assertThat(result.refId()).isNull();
    }

    @Test
    @DisplayName("an unreachable gateway throws rather than reporting a failure")
    void unreachableGatewayThrows() {
        // The distinction the whole design rests on: a refusal is an answer, silence is not.
        // Reporting silence as a failure would cancel orders that may well have been paid for.
        server.expect(requestTo(BASE + "/pg/v4/payment/verify.json"))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.verify(1000L, "A1"))
            .isInstanceOf(ZarinpalUnavailableException.class);
    }

    @Test
    @DisplayName("a non-JSON response is treated as the gateway being unavailable")
    void garbageResponse() {
        // An HTML maintenance page has been known to arrive with a 200.
        server.expect(requestTo(BASE + "/pg/v4/payment/verify.json"))
            .andRespond(withSuccess("<html>down for maintenance</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.verify(1000L, "A1"))
            .isInstanceOf(ZarinpalUnavailableException.class);
    }

    @Test
    @DisplayName("http mode without a merchant id refuses to start")
    void requiresMerchantId() {
        // Fails at startup rather than on the first customer's payment.
        assertThatThrownBy(() -> new HttpZarinpalClient(RestClient.builder(),
            new ZarinpalProperties("", BASE, "http://cb", "http://r", "http"), new ObjectMapper()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("merchant-id");
    }

    @Test
    @DisplayName("the shopper is sent to StartPay with their authority")
    void startPayUrl() {
        assertThat(new ZarinpalProperties(MERCHANT, BASE, "c", "r", "http").startPayUrl("A1"))
            .isEqualTo(BASE + "/pg/StartPay/A1");
    }
}
