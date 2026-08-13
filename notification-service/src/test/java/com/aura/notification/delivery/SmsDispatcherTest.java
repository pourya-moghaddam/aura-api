package com.aura.notification.delivery;

import com.aura.notification.sms.SmsGateway;
import com.aura.notification.sms.SmsReceipt;
import com.aura.notification.sms.SmsRejectedException;
import com.aura.notification.sms.SmsUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How a failed send is answered.
 *
 * <p>Every test here is about the same defect in the version this replaced: it caught every
 * exception, logged it, and returned normally. The binder saw a clean consume, committed the
 * offset, and a shopper waited for a code that was never coming and never retried.
 */
@ExtendWith(MockitoExtension.class)
class SmsDispatcherTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final String PHONE = "+989121234567";

    @Mock
    private DeliveryLog deliveryLog;

    @Mock
    private SmsGateway gateway;

    private SmsDispatcher dispatcher;
    private SmsDelivery claimed;

    @BeforeEach
    void setUp() {
        dispatcher = new SmsDispatcher(deliveryLog, gateway);
        claimed = SmsDelivery.claimed(EVENT, NotificationKind.OTP, PHONE);
        claimed.setId(1L);
    }

    private void claimSucceeds() {
        when(deliveryLog.claim(EVENT, NotificationKind.OTP, PHONE))
            .thenReturn(Optional.of(claimed));
    }

    @Test
    @DisplayName("a successful send is recorded with the provider's reference")
    void recordsASuccess() {
        claimSucceeds();
        when(gateway.sendTemplate(PHONE, 42, Map.of("OTP", "1234")))
            .thenReturn(new SmsReceipt("provider-9", BigDecimal.ONE));

        dispatcher.sendTemplate(EVENT, NotificationKind.OTP, PHONE, 42, Map.of("OTP", "1234"));

        verify(deliveryLog).recordSent(eq(1L), any(SmsReceipt.class), eq(42), eq(null));
    }

    @Test
    @DisplayName("an already-settled event is not sent again")
    void redeliveryDoesNotResend() {
        // The reason the delivery log exists. An SMS costs money and arrives on a real phone, and
        // a second OTP leaves the shopper holding two codes with no way to tell which one works.
        when(deliveryLog.claim(EVENT, NotificationKind.OTP, PHONE)).thenReturn(Optional.empty());

        dispatcher.sendTemplate(EVENT, NotificationKind.OTP, PHONE, 42, Map.of("OTP", "1234"));

        verify(gateway, never()).sendTemplate(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a transient failure is rethrown so the binder retries it")
    void transientFailureIsRethrown() {
        claimSucceeds();
        when(gateway.sendTemplate(anyString(), anyInt(), any()))
            .thenThrow(new SmsUnavailableException("connection reset"));

        assertThatThrownBy(() ->
            dispatcher.sendTemplate(EVENT, NotificationKind.OTP, PHONE, 42, Map.of()))
            .isInstanceOf(SmsUnavailableException.class);

        // Still PENDING, deliberately: the retry has to be able to claim the same row again rather
        // than being turned away as a duplicate of an attempt that never sent anything.
        verify(deliveryLog).recordAttempt(eq(1L), anyString());
        verify(deliveryLog, never()).recordFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("a permanent refusal is recorded and not retried")
    void permanentFailureIsSwallowedOnPurpose() {
        claimSucceeds();
        when(gateway.sendTemplate(anyString(), anyInt(), any()))
            .thenThrow(new SmsRejectedException("sms.ir status=-1 message=bad template"));

        // Returning normally lets the offset commit. Another lap round the queue would be refused
        // identically, three times, and then dead-letter - while every message behind it waits.
        dispatcher.sendTemplate(EVENT, NotificationKind.OTP, PHONE, 42, Map.of());

        verify(deliveryLog).recordFailed(eq(1L), anyString());
    }

    @Test
    @DisplayName("free text records what was actually said")
    void freeTextRecordsTheBody() {
        when(deliveryLog.claim(EVENT, NotificationKind.ORDER_STATUS, PHONE))
            .thenReturn(Optional.of(claimed));
        when(gateway.sendText(PHONE, "سفارش شما ارسال شد"))
            .thenReturn(new SmsReceipt("provider-1", BigDecimal.ZERO));

        dispatcher.sendText(EVENT, NotificationKind.ORDER_STATUS, PHONE, "سفارش شما ارسال شد");

        // Unlike an OTP, the text is safe to keep: the recipient can read it on their own phone,
        // and without it the log cannot tell an operator what was sent.
        verify(deliveryLog).recordSent(eq(1L), any(SmsReceipt.class), eq(null),
            eq("سفارش شما ارسال شد"));
    }

    @Test
    @DisplayName("an event with no phone is dropped rather than retried forever")
    void noPhoneIsDropped() {
        dispatcher.sendText(EVENT, NotificationKind.ORDER_STATUS, null, "anything");
        dispatcher.sendText(EVENT, NotificationKind.ORDER_STATUS, "  ", "anything");

        // No retry can conjure a phone number, so three attempts and a dead letter would only add
        // noise to a queue someone has to read.
        verify(deliveryLog, never()).claim(any(), any(), any());
        assertThat(claimed.getStatus()).isEqualTo(DeliveryStatus.PENDING);
    }
}
