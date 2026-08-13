package com.aura.notification.sms.smsir;

import com.aura.notification.config.SmsProperties;
import com.aura.notification.sms.SmsReceipt;
import com.aura.notification.sms.SmsRejectedException;
import com.aura.notification.sms.SmsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Which failures are worth another try.
 *
 * <p>The distinction is the whole reason this class exists. Retrying a timeout eventually works;
 * retrying a bad template id is refused identically three times and then dead-letters, while every
 * message behind it waits for a queue that cannot move.
 */
class SmsIrGatewayTest {

    private static final String VERIFY = "https://sms.test/v1/send/verify";
    private static final String BULK = "https://sms.test/v1/send/bulk";

    private MockRestServiceServer server;
    private SmsIrGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new SmsIrGateway(builder.build(), properties("a-key"));
    }

    private SmsProperties properties(String apiKey) {
        return new SmsProperties("http", new SmsProperties.SmsIrProperties(
            apiKey, 42, "https://sms.test/v1", "/send/verify", "/send/bulk", "30007", "OTP"));
    }

    @Test
    @DisplayName("a template send returns the provider's reference")
    void templateSendSucceeds() {
        server.expect(requestTo(VERIFY))
            .andExpect(header("x-api-key", "a-key"))
            .andExpect(jsonPath("$.mobile").value("+989121234567"))
            .andExpect(jsonPath("$.templateId").value(42))
            .andExpect(jsonPath("$.parameters[0].name").value("OTP"))
            .andRespond(withSuccess("""
                {"status":1,"message":"ok","data":{"messageId":9001,"cost":1.5}}
                """, MediaType.APPLICATION_JSON));

        SmsReceipt receipt = gateway.sendTemplate("+989121234567", 42, Map.of("OTP", "1234"));

        assertThat(receipt.messageId()).isEqualTo("9001");
        assertThat(receipt.cost()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("a free-text send goes out on the account's own line")
    void freeTextSendSucceeds() {
        server.expect(requestTo(BULK))
            .andExpect(jsonPath("$.lineNumber").value("30007"))
            .andExpect(jsonPath("$.messageText").value("سفارش شما ارسال شد"))
            .andExpect(jsonPath("$.mobiles[0]").value("+989121234567"))
            .andRespond(withSuccess("""
                {"status":1,"message":"ok","data":{"packId":7,"messageIds":[551],"cost":2.0}}
                """, MediaType.APPLICATION_JSON));

        assertThat(gateway.sendText("+989121234567", "سفارش شما ارسال شد").messageId())
            .isEqualTo("551");
    }

    @Test
    @DisplayName("a 5xx is worth retrying")
    void serverErrorIsRetryable() {
        server.expect(requestTo(VERIFY)).andRespond(withServerError());

        assertThatThrownBy(() -> gateway.sendTemplate("+989121234567", 42, Map.of()))
            .isInstanceOf(SmsUnavailableException.class);
    }

    @Test
    @DisplayName("being rate limited is worth retrying, unlike every other 4xx")
    void rateLimitIsRetryable() {
        // The one client error that becomes true again on its own. Treating it as permanent throws
        // away real messages during exactly the traffic spike that caused it.
        server.expect(requestTo(VERIFY)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> gateway.sendTemplate("+989121234567", 42, Map.of()))
            .isInstanceOf(SmsUnavailableException.class);
    }

    @Test
    @DisplayName("a 4xx is a refusal, not an outage")
    void clientErrorIsPermanent() {
        server.expect(requestTo(VERIFY)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> gateway.sendTemplate("+989121234567", 42, Map.of()))
            .isInstanceOf(SmsRejectedException.class);
    }

    @Test
    @DisplayName("a 200 carrying a failure status is still a refusal")
    void applicationLevelFailureIsPermanent() {
        // sms.ir answers 200 with status=-1 for a bad template or an empty account. Reading only
        // the HTTP code would record a message as sent that the provider never accepted.
        server.expect(requestTo(VERIFY)).andRespond(withSuccess("""
            {"status":-1,"message":"template not found","data":null}
            """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.sendTemplate("+989121234567", 42, Map.of()))
            .isInstanceOf(SmsRejectedException.class)
            .hasMessageContaining("template not found");
    }

    @Test
    @DisplayName("the error carries the provider's words and not the request")
    void errorsDoNotLeakTheCode() {
        server.expect(requestTo(VERIFY)).andRespond(withSuccess("""
            {"status":-1,"message":"no credit","data":null}
            """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.sendTemplate("+989121234567", 42, Map.of("OTP", "918273")))
            .isInstanceOf(SmsRejectedException.class)
            // This message ends up in last_error, in the database, forever. The one-time code must
            // not travel with it.
            .hasMessageNotContaining("918273");
    }

    @Test
    @DisplayName("http mode without a key refuses to start")
    void httpModeNeedsAKey() {
        // Starting and failing every send looks like a provider outage and sends someone hunting
        // in the wrong place for an afternoon.
        assertThatThrownBy(() -> new SmsIrGateway(RestClient.builder().build(), properties("")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SMS_IR_API_KEY");
    }
}
