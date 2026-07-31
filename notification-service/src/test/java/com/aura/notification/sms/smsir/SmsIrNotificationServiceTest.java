package com.aura.notification.sms.smsir;

import com.aura.common.event.OtpRequestedEvent;
import com.aura.notification.config.RestClientConfig;
import com.aura.notification.config.SmsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.test.autoconfigure.RestClientTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RestClientTest(SmsIrNotificationService.class)
@Import(RestClientConfig.class)
@EnableConfigurationProperties(SmsProperties.class)
@TestPropertySource(properties = {
    "aura.notification.sms.sms-ir.api-key=KqPGIqGFg2b23FXXDnzmXyPq0Ta4VYyrQL0XhrxQu1lfO0Rc",
    "aura.notification.sms.sms-ir.otp-template-id=823095",
    "aura.notification.sms.sms-ir.base-url=https://api.sms.ir/v1",
    "aura.notification.sms.sms-ir.verify-path=/send/verify",
    "aura.notification.sms.sms-ir.otp-parameter-name=OTP"
})
class SmsIrNotificationServiceTest {

    @Autowired
    private MockRestServiceServer server;

    @Autowired
    private SmsIrNotificationService service;

    @Test
    void sendSms_Success() {
        String expectedResponseBody = """
            {
                "status": 1,
                "message": "موفق",
                "data": {
                    "messageId": 89545112,
                    "cost": 1.0
                }
            }
            """;

        server.expect(requestTo("https://api.sms.ir/v1/send/verify"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("x-api-key", "KqPGIqGFg2b23FXXDnzmXyPq0Ta4VYyrQL0XhrxQu1lfO0Rc"))
            .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
            .andExpect(content().json("""
                {
                    "mobile": "+989120000000",
                    "templateId": 823095,
                    "parameters": [
                        {
                            "name": "OTP",
                            "value": "123456"
                        }
                    ]
                }
                """))
            .andRespond(withSuccess(expectedResponseBody, MediaType.APPLICATION_JSON));

        OtpRequestedEvent event = new OtpRequestedEvent("+989120000000", "123456", "Your code is 123456");
        service.sendSms(event);

        server.verify();
    }
}