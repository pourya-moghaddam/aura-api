package com.aura.notification.order;

import com.aura.common.events.OrderItemStatusChangedEvent;
import com.aura.notification.delivery.NotificationKind;
import com.aura.notification.delivery.SmsDispatcher;
import com.aura.notification.template.MessageRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * When a status change is worth a text message, and what it says.
 */
@ExtendWith(MockitoExtension.class)
class OrderStatusNotifierTest {

    private static final String PHONE = "+989121234567";
    private static final String TRACE = "ABCDEFGHJK";

    @Mock
    private SmsDispatcher dispatcher;

    private OrderStatusNotifier notifier;

    private OrderStatusNotifier notifier() {
        if (notifier == null) {
            notifier = new OrderStatusNotifier(dispatcher, new MessageRenderer());
        }
        return notifier;
    }

    private OrderItemStatusChangedEvent event(String previous, String next, String orderStatus) {
        return new OrderItemStatusChangedEvent(
            UUID.randomUUID(), Instant.now(), 99L, 7L, TRACE, 5L, 11L, 22L,
            "کفش ورزشی", 1, previous, next, orderStatus, PHONE, "علی رضایی", null);
    }

    private String bodySent() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).sendText(anyString(), any(), eq(NotificationKind.ORDER_STATUS),
            eq(PHONE), body.capture());
        return body.getValue();
    }

    private String keyUsed() {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(dispatcher).sendText(key.capture(), any(), any(), anyString(), anyString());
        return key.getValue();
    }

    @Test
    @DisplayName("a dispatch tells the buyer what shipped and how to look it up")
    void shippedIsAnnounced() {
        notifier().statusChanged(event("PROCESSING", "SHIPPED", "PROCESSING"));

        assertThat(bodySent()).isEqualTo("ارسال شد:\nکفش ورزشی\nکد پیگیری: " + TRACE);
    }

    @Test
    @DisplayName("a seller starting work is worth saying")
    void processingIsAnnounced() {
        // Between paying and this, the shopper has no evidence a real person saw the order.
        notifier().statusChanged(event("PENDING", "PROCESSING", "PENDING"));

        assertThat(bodySent()).contains("در حال آماده‌سازی");
    }

    @Test
    @DisplayName("a cancelled line is announced per item, since another seller's is unaffected")
    void cancelledIsAnnouncedPerItem() {
        notifier().statusChanged(event("PROCESSING", "CANCELLED", "PROCESSING"));

        assertThat(bodySent()).contains("لغو شد");
    }

    @Test
    @DisplayName("delivery is announced only once the whole order has arrived")
    void deliveredWaitsForTheWholeOrder() {
        // One seller's parcel landing while another's is still in transit is not "your order was
        // delivered". Saying so sends the shopper looking for a box that does not exist yet.
        notifier().statusChanged(event("SHIPPED", "DELIVERED", "SHIPPED"));

        verify(dispatcher, never()).sendText(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the delivered message is about the order, not one of its items")
    void deliveredIsAboutTheOrder() {
        notifier().statusChanged(event("SHIPPED", "DELIVERED", "DELIVERED"));

        assertThat(bodySent()).isEqualTo("سفارش شما تحویل داده شد.\nکد پیگیری: " + TRACE);
    }

    @Test
    @DisplayName("delivery deduplicates on the order so one parcel is announced once")
    void deliveredDeduplicatesOnTheOrder() {
        notifier().statusChanged(event("SHIPPED", "DELIVERED", "DELIVERED"));

        // A three-item order raises three events when the last one lands. Keyed on the event id
        // the shopper would get three identical texts and read them as a mistake.
        assertThat(keyUsed()).isEqualTo("order:99:DELIVERED");
    }

    @Test
    @DisplayName("everything else deduplicates on the event, so two items both get a message")
    void itemMessagesDeduplicateOnTheEvent() {
        OrderItemStatusChangedEvent shipped = event("PROCESSING", "SHIPPED", "PROCESSING");

        notifier().statusChanged(shipped);

        assertThat(keyUsed()).isEqualTo(shipped.eventId().toString());
    }

    @Test
    @DisplayName("a status that did not change is not news")
    void unchangedStatusIsSilent() {
        // A seller re-saving the same status. Sending anyway trains shoppers to ignore these
        // messages, which costs more than the SMS does.
        notifier().statusChanged(event("SHIPPED", "SHIPPED", "SHIPPED"));

        verify(dispatcher, never()).sendText(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an order coming into existence is not worth a text")
    void pendingIsSilent() {
        // Every item starts here, and the shopper was looking at the confirmation page when it did.
        notifier().statusChanged(event(null, "PENDING", "PENDING"));

        verify(dispatcher, never()).sendText(anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("an unrecognised status is ignored rather than dead-lettered")
    void unknownStatusIsSilent() {
        // order-service could add a status before this service knows about it. Failing here would
        // stall every notification behind a message that is not actually broken.
        notifier().statusChanged(event("SHIPPED", "RETURNED", "SHIPPED"));

        verify(dispatcher, never()).sendText(anyString(), any(), any(), anyString(), anyString());
    }
}
