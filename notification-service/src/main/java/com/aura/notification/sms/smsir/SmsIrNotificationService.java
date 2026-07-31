package com.aura.notification.sms.smsir;

import com.aura.common.event.OtpRequestedEvent;
import com.aura.notification.config.SmsProperties;
import com.aura.notification.sms.SmsNotificationService;
import com.aura.notification.sms.smsir.dto.SmsIrVerifyParameter;
import com.aura.notification.sms.smsir.dto.SmsIrVerifyRequest;
import com.aura.notification.sms.smsir.dto.SmsIrVerifyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Service("smsIrNotificationService")
@RequiredArgsConstructor
public class SmsIrNotificationService implements SmsNotificationService {

    private final RestClient restClient;
    private final SmsProperties smsProperties;

    @Override
    public void sendSms(OtpRequestedEvent event) {
        log.info("Sending Verify SMS via sms.ir to mobile: {}", event.phone());

        SmsIrVerifyRequest requestBody = new SmsIrVerifyRequest(
            event.phone(),
            smsProperties.smsIr().otpTemplateId(),
            List.of(new SmsIrVerifyParameter(smsProperties.smsIr().otpParameterName(), event.otpCode()))
        );

        try {
            SmsIrVerifyResponse response = restClient.post()
                .uri(smsProperties.smsIr().baseUrl() + smsProperties.smsIr().verifyPath())
                .header("x-api-key", smsProperties.smsIr().apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(SmsIrVerifyResponse.class);

            if (response != null && response.status() == 1) {
                log.info("SMS sent successfully! MessageId: {}, Cost: {}",
                    response.data().messageId(), response.data().cost());
            } else {
                log.error("Failed to send SMS via sms.ir. Response: {}", response);
            }
        } catch (Exception e) {
            log.error("Exception occurred while calling sms.ir API for phone {}", event.phone(), e);
        }
    }
}
