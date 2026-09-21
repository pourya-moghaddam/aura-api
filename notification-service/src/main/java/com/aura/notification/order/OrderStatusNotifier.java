package com.aura.notification.order;

import com.aura.common.events.OrderItemStatusChangedEvent;
import com.aura.notification.delivery.NotificationKind;
import com.aura.notification.delivery.SmsDispatcher;
import com.aura.notification.template.MessageRenderer;
import com.aura.notification.template.MessageTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells the buyer when a seller moves one of their lines.
 *
 * <p>Per item rather than per order, because requirement 8 gives each seller control of their own
 * lines and one basket can hold several sellers' products. A shopper whose order is split between
 * two sellers wants to hear about each dispatch as it happens.
 *
 * <p>Delivery is the exception, and the only interesting decision in this class — see
 * {@link #dedupeKeyFor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusNotifier {

    private final SmsDispatcher dispatcher;
    private final MessageRenderer renderer;

    public void statusChanged(OrderItemStatusChangedEvent event) {
        if (isNotAChange(event)) {
            // A seller re-saving the same status is not news. Sending anyway would train shoppers
            // to ignore these messages, which costs more than the SMS does.
            log.debug("Item {} did not actually change status ({})", event.orderItemId(),
                event.newStatus());
            return;
        }

        MessageTemplate template = templateFor(event);
        if (template == null) {
            log.debug("Nothing to say about {} -> {}", event.previousStatus(), event.newStatus());
            return;
        }

        if (template == MessageTemplate.ORDER_DELIVERED && !isOrderDelivered(event)) {
            // One seller's parcel arrived but the order has not. Telling the shopper their order
            // was delivered while another seller's box is still in transit is worse than silence.
            log.debug("Order {} is not delivered yet ({})", event.orderId(), event.orderStatus());
            return;
        }

        dispatcher.sendText(
            dedupeKeyFor(event, template),
            event.eventId(),
            NotificationKind.ORDER_STATUS,
            event.buyerPhone(),
            renderer.render(template, valuesFor(event, template)));
    }

    /**
     * What "already sent" means for this message.
     *
     * <p>The event id for anything about a single item. But a delivered order raises one event per
     * item, and a shopper who hears "your order has been delivered" three times for one parcel
     * reads it as a mistake — so that message deduplicates on the order instead, and whichever
     * item's event arrives first is the one that sends.
     */
    private String dedupeKeyFor(OrderItemStatusChangedEvent event, MessageTemplate template) {
        return template == MessageTemplate.ORDER_DELIVERED
            ? "order:" + event.orderId() + ":DELIVERED"
            : event.eventId().toString();
    }

    private boolean isNotAChange(OrderItemStatusChangedEvent event) {
        return event.newStatus() == null || event.newStatus().equals(event.previousStatus());
    }

    private boolean isOrderDelivered(OrderItemStatusChangedEvent event) {
        return "DELIVERED".equals(event.orderStatus());
    }

    private MessageTemplate templateFor(OrderItemStatusChangedEvent event) {
        return switch (event.newStatus()) {
            case "PROCESSING" -> MessageTemplate.ORDER_PROCESSING;
            case "SHIPPED" -> MessageTemplate.ORDER_SHIPPED;
            case "DELIVERED" -> MessageTemplate.ORDER_DELIVERED;
            case "CANCELLED" -> MessageTemplate.ORDER_CANCELLED;
            // PENDING is where every item starts. Nobody needs a text saying their order exists;
            // they were looking at the confirmation page when it was created.
            default -> null;
        };
    }

    private Map<String, String> valuesFor(OrderItemStatusChangedEvent event,
                                          MessageTemplate template) {
        Map<String, String> values = new HashMap<>();
        values.put("trace", event.traceCode());
        if (template.placeholders().contains("product")) {
            values.put("product", event.productName());
        }
        return values;
    }
}
