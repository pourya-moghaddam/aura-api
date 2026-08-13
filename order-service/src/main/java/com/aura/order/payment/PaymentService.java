package com.aura.order.payment;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.catalog.CatalogGateway;
import com.aura.common.events.OrderPaidEvent;
import com.aura.common.events.Topics;
import com.aura.order.order.Order;
import com.aura.order.order.OrderItemRepository;
import com.aura.order.outbox.OutboxWriter;
import com.aura.order.order.OrderRepository;
import com.aura.order.order.PaymentStatus;
import com.aura.order.order.TraceCodes;
import com.aura.order.payment.dto.PaymentStartResponse;
import com.aura.order.payment.zarinpal.ZarinpalClient;
import com.aura.order.payment.zarinpal.ZarinpalProperties;
import com.aura.order.payment.zarinpal.ZarinpalUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

/**
 * Taking money for an order, and the far harder problem of knowing whether it was taken.
 *
 * <p>Three rules run through everything here, and each of them is a way of losing money or a
 * customer's trust if broken:
 *
 * <ol>
 *   <li><strong>{@code Status=OK} on the callback proves nothing.</strong> It is a query parameter
 *       in the shopper's own browser. Only the server-to-server verify is evidence, which is why
 *       the callback does nothing but trigger one.</li>
 *   <li><strong>Verification must be idempotent.</strong> Zarinpal answers 101 — "already
 *       verified" — for every verify after the first, and that is a <em>success</em>. Reading it
 *       as a failure cancels an order that was paid for.</li>
 *   <li><strong>Silence is not failure.</strong> An unreachable gateway leaves the payment pending
 *       so reconciliation can ask again. Marking it failed would release stock and cancel an order
 *       that may well have been paid.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OrderRepository orderRepository;
    private final CatalogGateway catalogGateway;
    private final ZarinpalClient zarinpalClient;
    private final ZarinpalProperties zarinpalProperties;
    private final OrderItemRepository orderItemRepository;
    private final OutboxWriter outboxWriter;

    /**
     * Starts a payment attempt and returns where to send the shopper.
     *
     * <p>Identified by trace code rather than by id, because the shopper who has to pay may be a
     * guest with no account and the trace code is the only thing they hold.
     */
    @Transactional
    public PaymentStartResponse start(String traceCode) {
        Order order = orderRepository.findByTraceCode(TraceCodes.normalise(traceCode))
            .orElseThrow(() -> ResourceNotFoundException.of("Order", traceCode));

        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BusinessRuleException("order-already-paid",
                "This order has already been paid for.");
        }
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            // Expired or cancelled: the stock behind it is gone, so sending the shopper to a
            // gateway would take money for something that can no longer be shipped.
            throw new BusinessRuleException("order-not-payable",
                "This order can no longer be paid for. Please place a new one.");
        }

        Payment payment = paymentRepository.save(Payment.forOrder(order.getId(), order.getTotal()));

        ZarinpalClient.RequestResult result = zarinpalClient.request(
            order.getTotal(),
            "Aura order " + order.getTraceCode(),
            zarinpalProperties.callbackUrl(),
            order.getBuyerPhone(),
            order.getTraceCode());

        record(payment, "REQUEST", result.raw());

        if (!result.isSuccess()) {
            payment.markFailed(PaymentStatus.FAILED);
            paymentRepository.save(payment);
            log.warn("Zarinpal refused a payment request for order {}: code {} {}",
                order.getTraceCode(), result.code(), result.message());

            throw new BusinessRuleException("payment-request-failed",
                "The payment gateway could not start this payment. Please try again shortly.");
        }

        payment.setAuthority(result.authority());
        payment.touch();
        paymentRepository.save(payment);

        log.info("Payment {} started for order {} with authority {}",
            payment.getId(), order.getTraceCode(), result.authority());

        return new PaymentStartResponse(order.getTraceCode(), result.authority(),
            zarinpalProperties.startPayUrl(result.authority()), order.getTotal());
    }

    /**
     * Settles the attempt the shopper has just come back from.
     *
     * <p>{@code status} is Zarinpal's {@code Status} query parameter. NOK means the shopper
     * cancelled or the payment failed, and is taken at face value only because it can lose the shop
     * nothing — it is the OK case that is never trusted without verifying.
     *
     * @return the settled payment, so the caller can redirect accordingly
     */
    @Transactional
    public Payment settleCallback(String authority, String status) {
        Payment payment = paymentRepository.lockByAuthority(authority)
            .orElseThrow(() -> ResourceNotFoundException.of("Payment", authority));

        record(payment, "CALLBACK", "{\"authority\":\"" + authority + "\",\"status\":\""
            + status + "\"}");

        if (payment.isSettled()) {
            // Already dealt with, by an earlier callback or by reconciliation. Returning it
            // unchanged is what makes a shopper's refresh harmless.
            log.info("Callback for payment {} which is already {}", payment.getId(), payment.getStatus());
            return payment;
        }

        if (!"OK".equalsIgnoreCase(status)) {
            return abandon(payment, PaymentStatus.CANCELLED, "the shopper cancelled");
        }

        return verifyAndSettle(payment);
    }

    /**
     * Asks the gateway what happened and writes down the answer.
     *
     * <p>The one place a payment becomes PAID. Everything else — the callback, the sweep — exists
     * only to get here.
     */
    private Payment verifyAndSettle(Payment payment) {
        ZarinpalClient.VerifyResult result;
        try {
            result = zarinpalClient.verify(payment.getAmount(), payment.getAuthority());
        } catch (ZarinpalUnavailableException e) {
            // Left pending on purpose. The sweep will ask again; guessing here is how a paid order
            // gets cancelled.
            log.error("Could not verify payment {}; leaving it pending", payment.getId(), e);
            throw new BusinessRuleException("payment-verification-unavailable",
                "We could not confirm your payment yet. It will be confirmed shortly.");
        }

        record(payment, "VERIFY", result.raw());

        if (!result.isPaid()) {
            log.warn("Payment {} was not verified: code {} {}",
                payment.getId(), result.code(), result.message());
            return abandon(payment, PaymentStatus.FAILED, "verification returned " + result.code());
        }

        payment.markPaid(result.refId(), result.cardPan());
        paymentRepository.save(payment);

        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
        order.setPaymentStatus(PaymentStatus.PAID);
        order.setPaidAt(OffsetDateTime.now());
        order.touch();
        orderRepository.save(order);

        // What sold, for the read model. Written to the outbox inside this transaction, so a
        // payment that is recorded is always accompanied by the event describing it - and one that
        // rolls back takes the event with it rather than inflating a product's popularity for a
        // sale that never happened.
        outboxWriter.write(Topics.ORDER_PAID, String.valueOf(order.getId()), paidEvent(order));

        // The held units leave stock for good. Best-effort by design: catalog's own reconciliation
        // is the backstop, and failing the callback here would tell a customer their successful
        // payment failed.
        catalogGateway.commitStock(order.getId());

        log.info("Payment {} confirmed for order {} (ref {}{})", payment.getId(),
            order.getTraceCode(), result.refId(),
            result.isAlreadyVerified() ? ", already verified" : "");

        return payment;
    }

    /** Marks the attempt dead and puts the stock back. */
    private Payment abandon(Payment payment, PaymentStatus status, String why) {
        payment.markFailed(status);
        paymentRepository.save(payment);

        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();

        // Only if nothing else has paid for it. A shopper who abandons one attempt and completes
        // another must not have the successful order cancelled by the abandoned one's callback.
        if (order.getPaymentStatus() == PaymentStatus.PENDING) {
            order.setPaymentStatus(status);
            order.touch();
            orderRepository.save(order);
            catalogGateway.releaseStock(order.getId());

            log.info("Order {} {} because {}", order.getTraceCode(), status, why);
        }
        return payment;
    }

    /**
     * Asks the gateway about payments nobody came back from.
     *
     * <p>Callbacks are lost routinely — the browser is closed on the bank's page, the phone rings,
     * the network drops between the gateway and the shop. Without this the customer has paid and
     * the shop believes they have not, which is the single worst state this system can be in and
     * the one a customer notices first.
     *
     * @return how many payments were settled
     */
    @Transactional
    public int reconcile(java.time.Duration olderThan, int limit) {
        // Authorities, not entities. Loading the payments here would put them in the persistence
        // context, and the lock below would then hand back those same stale instances - so a
        // payment the shopper's callback settled in between still looks pending and gets settled
        // twice. Found by an integration test that had been passing on timing alone.
        List<String> stale = paymentRepository.findStaleAuthorities(PaymentStatus.PENDING,
            OffsetDateTime.now().minus(olderThan), PageRequest.of(0, limit));

        int settled = 0;
        for (String authority : stale) {
            // Read under the lock: the shopper's own callback may have arrived in between, and
            // settling the same payment twice is exactly what this is here to avoid.
            Optional<Payment> locked = paymentRepository.lockByAuthority(authority);
            if (locked.isEmpty() || locked.get().isSettled()) {
                continue;
            }

            try {
                Payment result = verifyAndSettle(locked.get());
                if (result.getStatus() != PaymentStatus.PENDING) {
                    settled++;
                }
            } catch (BusinessRuleException | ZarinpalUnavailableException e) {
                // Still unreachable. Left pending for the next sweep rather than guessed at.
                log.warn("Reconciliation could not settle payment {}: {}", authority, e.getMessage());
            }
        }

        if (settled > 0) {
            log.info("Reconciliation settled {} of {} stale payment(s)", settled, stale.size());
        }
        return settled;
    }

    @Transactional(readOnly = true)
    public List<Payment> forOrder(long orderId) {
        return paymentRepository.findByOrderIdOrderByIdDesc(orderId);
    }

    /**
     * Where the shopper's browser is finally sent, once the outcome is known.
     *
     * <p>Resolves the order itself rather than taking one from the caller, so the controller has
     * no reason to touch a repository — the layering ArchUnit enforces, and the reason it does is
     * that a transaction boundary in a controller is a transaction boundary in the wrong place.
     */
    @Transactional(readOnly = true)
    public String resultUrlFor(Payment payment) {
        Order order = orderRepository.findById(payment.getOrderId()).orElseThrow();
        return zarinpalProperties.resultUrl()
            + "?trace=" + order.getTraceCode()
            + "&status=" + payment.getStatus();
    }

    /** What the shop believes about an order's payment, for a status poll or a support enquiry. */
    @Transactional(readOnly = true)
    public PaymentStatus statusOf(String traceCode) {
        return orderRepository.findByTraceCode(TraceCodes.normalise(traceCode))
            .map(Order::getPaymentStatus)
            .orElseThrow(() -> ResourceNotFoundException.of("Order", traceCode));
    }

    /**
     * Where a shopper goes when the outcome is not yet known — an unreachable gateway, or a
     * callback for an authority we cannot place. Deliberately not a failure page: they may well
     * have paid, and reconciliation will settle it within minutes.
     */
    public String resultUrlPending(String authority) {
        return zarinpalProperties.resultUrl() + "?status=PENDING&authority=" + authority;
    }

    private OrderPaidEvent paidEvent(Order order) {
        List<OrderPaidEvent.Line> lines = orderItemRepository
            .findByOrderIdOrderByIdAsc(order.getId()).stream()
            .map(item -> new OrderPaidEvent.Line(
                item.getProductId(), item.getVariantId(), item.getQuantity()))
            .toList();

        return new OrderPaidEvent(UUID.randomUUID(), Instant.now(),
            order.getId(), order.getTraceCode(), lines);
    }

    private void record(Payment payment, String type, String raw) {
        paymentEventRepository.save(PaymentEvent.of(payment.getId(), type, raw));
    }
}
