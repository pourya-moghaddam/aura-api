package com.aura.order.link;

import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.config.OrderLinkProperties;
import com.aura.order.link.dto.CreateOrderLinkRequest;
import com.aura.order.link.dto.OrderLinkResponse;
import com.aura.order.order.CheckoutService;
import com.aura.order.order.Order;
import com.aura.order.order.OrderItemRepository;
import com.aura.order.order.OrderRepository;
import com.aura.order.order.PaymentStatus;
import com.aura.order.order.dto.CheckoutRequest;
import com.aura.order.order.dto.OrderResponse;
import com.aura.order.payment.PaymentService;
import com.aura.order.payment.dto.PaymentStartResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Orders a seller composes and sends a buyer a link to pay for — requirement 1.
 *
 * <p>The buyer is typically on the telephone: no account, no basket, and no intention of finding
 * the products themselves. The seller builds the order; the buyer receives a link, sees exactly
 * what they are being charged for, and pays.
 *
 * <p>The token is the only thing protecting that order — anyone holding the link can pay for it —
 * so it is 256 random bits, stored hashed, and short-lived. It is short-lived for a second reason
 * too: the stock is held from the moment the order is written, and a link with a month's life
 * keeps goods off the shelf for a buyer who has stopped answering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerOrderLinkService {

    private final SellerOrderLinkRepository linkRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CheckoutService checkoutService;
    private final PaymentService paymentService;
    private final OrderLinkProperties properties;

    /**
     * Writes the order and mints the link.
     *
     * <p>The raw token is returned exactly once and never stored. A seller who loses it composes
     * a new order — which is the correct outcome, since the alternative is a token we could hand
     * back to anyone who asked.
     */
    @Transactional
    public OrderLinkResponse create(long sellerId, CreateOrderLinkRequest request) {
        List<CheckoutService.RequestedLine> lines = request.items().stream()
            .map(item -> new CheckoutService.RequestedLine(item.variantId(), item.quantity()))
            .toList();

        // The ordinary checkout path, restricted to this seller's own products. Same validation,
        // same pricing from catalog, same stock hold taken last.
        OrderResponse order = checkoutService.placeForSeller(sellerId, asCheckoutRequest(request),
            lines, null);

        String rawToken = LinkTokens.generate();
        SellerOrderLink link = linkRepository.save(SellerOrderLink.of(order.id(), sellerId,
            LinkTokens.hash(rawToken), OffsetDateTime.now().plus(properties.validity())));

        log.info("Seller {} composed order {} and minted a link expiring {}",
            sellerId, order.traceCode(), link.getExpiresAt());

        return OrderLinkResponse.created(link, order.traceCode(), order.total(),
            properties.urlFor(rawToken));
    }

    /**
     * What the buyer sees when they open the link.
     *
     * <p>Anonymous, and the whole order — they are about to pay for it, so they are entitled to
     * see every line, the delivery charge and the total.
     */
    @Transactional(readOnly = true)
    public OrderResponse view(String rawToken) {
        SellerOrderLink link = requireUsable(linkRepository.findByTokenHash(LinkTokens.hash(rawToken)));

        Order order = orderRepository.findById(link.getOrderId()).orElseThrow();
        return OrderResponse.of(order, orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()));
    }

    /**
     * Starts the payment for a link, and spends it.
     *
     * <p>The link is marked used when payment <em>begins</em>, not when it succeeds. It is a
     * single-use credential; leaving it open until settlement would let a second person start a
     * second payment for the same order while the first is at their bank.
     *
     * <p>The lock is what makes that true under a race — two taps on the same link a moment apart
     * both find it unused otherwise.
     */
    @Transactional
    public PaymentStartResponse pay(String rawToken) {
        SellerOrderLink link = requireUsable(
            linkRepository.lockByTokenHash(LinkTokens.hash(rawToken)));

        Order order = orderRepository.findById(link.getOrderId()).orElseThrow();
        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessRuleException("order-not-payable",
                order.getPaymentStatus() == PaymentStatus.PAID
                    ? "This order has already been paid for."
                    : "This order can no longer be paid for.");
        }

        PaymentStartResponse payment = paymentService.start(order.getTraceCode());

        link.markUsed();
        linkRepository.save(link);

        return payment;
    }

    /** A seller's own links, so they can see which have been paid and which have lapsed. */
    @Transactional(readOnly = true)
    public List<OrderLinkResponse> listFor(long sellerId) {
        return linkRepository.findBySellerIdOrderByCreatedAtDesc(sellerId).stream()
            .map(link -> {
                Order order = orderRepository.findById(link.getOrderId()).orElseThrow();
                return OrderLinkResponse.existing(link, order.getTraceCode(), order.getTotal());
            })
            .toList();
    }

    /**
     * Resolves a link, refusing anything a buyer should not be able to act on.
     *
     * <p>Expired, spent and unknown all answer the same way. Distinguishing them would let someone
     * with a guessed token learn that it was nearly right.
     */
    private SellerOrderLink requireUsable(java.util.Optional<SellerOrderLink> found) {
        SellerOrderLink link = found
            .orElseThrow(() -> ResourceNotFoundException.of("Order link", "token"));

        if (link.isExpired() || link.isUsed()) {
            throw ResourceNotFoundException.of("Order link", "token");
        }
        return link;
    }

    /** The seller's request in the shape checkout already understands. */
    private CheckoutRequest asCheckoutRequest(CreateOrderLinkRequest request) {
        return new CheckoutRequest(request.buyerFirstName(), request.buyerLastName(),
            request.buyerPhone(), request.province(), request.city(), request.addressLine(),
            request.postalCode(), request.deliveryMethodId(), null, null);
    }
}
