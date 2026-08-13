package com.aura.order.order;

import com.aura.common.phone.IranianPhoneNumber;
import com.aura.common.web.error.BusinessRuleException;
import com.aura.common.web.error.ResourceNotFoundException;
import com.aura.order.cart.Cart;
import com.aura.order.cart.CartItem;
import com.aura.order.cart.CartItemRepository;
import com.aura.order.cart.CartOwner;
import com.aura.order.cart.CartRepository;
import com.aura.order.catalog.CatalogGateway;
import com.aura.order.catalog.VariantSnapshot;
import com.aura.order.delivery.DeliveryMethod;
import com.aura.order.delivery.DeliveryMethodService;
import com.aura.order.discount.DiscountService;
import com.aura.order.order.dto.CheckoutRequest;
import com.aura.order.order.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a basket into an order.
 *
 * <p>The ordering inside the transaction is the whole design, and it is chosen around one
 * question: which way should this fail? Prices and stock are re-read from catalog rather than
 * trusted from the cart, because minutes may have passed on the address form. The order row is
 * written before the stock is held, because the reservation is keyed by order id. And the remote
 * reservation is the <em>last</em> thing done, so a failure anywhere else rolls the order back
 * before any stock has moved.
 *
 * <p>If the transaction rolls back after the reservation succeeded, the hold is orphaned and
 * catalog's TTL sweep returns it within the quarter-hour. That is the failure this is arranged to
 * prefer: stock held briefly for nobody, rather than an order that exists with nothing held for
 * it — which is an oversell, and is discovered by a customer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    /** A trace code is 32^10; a clash means the random source is broken, not that we were unlucky. */
    private static final int TRACE_CODE_ATTEMPTS = 5;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogGateway catalogGateway;
    private final DeliveryMethodService deliveryMethodService;
    private final DiscountService discountService;

    @Transactional
    public OrderResponse checkout(CartOwner owner, CheckoutRequest request) {
        String idempotencyKey = blankToNull(request.idempotencyKey());

        OrderResponse replay = replayOf(idempotencyKey);
        if (replay != null) {
            return replay;
        }

        Cart cart = findCart(owner)
            .orElseThrow(() -> new BusinessRuleException("cart-empty",
                "Your basket is empty."));

        List<CartItem> cartItems = cartItemRepository.findByCartIdOrderByAddedAtAscIdAsc(cart.getId());
        if (cartItems.isEmpty()) {
            throw new BusinessRuleException("cart-empty", "Your basket is empty.");
        }

        List<RequestedLine> lines = cartItems.stream()
            .map(item -> new RequestedLine(item.getVariantId(), item.getQuantity()))
            .toList();

        OrderResponse placed = place(owner.isUser() ? owner.userId() : null, request, lines,
            OrderSource.CUSTOMER, idempotencyKey, null);

        // The basket has become an order. Leaving it would show the shopper their items still
        // waiting to be bought while they are on the payment page.
        cartItemRepository.deleteByCartId(cart.getId());
        cartRepository.delete(cart);

        return placed;
    }

    /**
     * Places an order a seller composed on a buyer's behalf — requirement 1's custom link.
     *
     * <p>Same path as an ordinary checkout, deliberately. The ordering inside it is what stops an
     * order existing with no stock held for it, and a second implementation would eventually get
     * that wrong in a way nobody notices until a customer is told their goods are not there.
     *
     * <p>{@code onlySellerId} restricts the lines to that seller's own products. Without it a
     * seller could compose an order from anyone's catalogue and take a link to it.
     */
    @Transactional
    public OrderResponse placeForSeller(long sellerId, CheckoutRequest request,
                                        List<RequestedLine> lines, String idempotencyKey) {
        OrderResponse replay = replayOf(idempotencyKey);
        return replay != null
            ? replay
            : place(null, request, lines, OrderSource.SELLER_LINK, idempotencyKey, sellerId);
    }

    /**
     * The order-writing path, shared by an ordinary checkout and a seller's composed order.
     *
     * <p>The sequence is the design and it is chosen around which way this should fail. Prices and
     * stock are re-read from catalog rather than trusted; the order row is written before the
     * stock is held, because catalog keys the reservation by order id; and the remote reservation
     * is <em>last</em>, so a failure anywhere else rolls the order back before any stock has moved.
     */
    private OrderResponse place(Long userId, CheckoutRequest request, List<RequestedLine> lines,
                                OrderSource source, String idempotencyKey, Long onlySellerId) {
        if (lines.isEmpty()) {
            throw new BusinessRuleException("cart-empty", "Your basket is empty.");
        }

        // Priced and checked against catalog now, not against what the cart remembers. The shopper
        // has been filling in an address; the shop may have moved on.
        Map<Long, VariantSnapshot> snapshots = catalogGateway.snapshotsFor(
            lines.stream().map(RequestedLine::variantId).toList());
        requireAllPurchasable(lines, snapshots);

        if (onlySellerId != null) {
            requireAllBelongTo(onlySellerId, lines, snapshots);
        }

        DeliveryMethod delivery = deliveryMethodService.requireSelectable(request.deliveryMethodId());

        long subtotal = lines.stream()
            .mapToLong(line -> snapshots.get(line.variantId()).unitPrice() * line.quantity())
            .sum();

        Order order = orderRepository.saveAndFlush(
            newOrder(userId, request, delivery, subtotal, idempotencyKey, source));

        List<OrderItem> items = lines.stream()
            .map(line -> toOrderItem(order.getId(), line, snapshots.get(line.variantId())))
            .toList();
        orderItemRepository.saveAll(items);

        // After the order exists, because the redemption row references it - and inside this
        // transaction, so an abandoned checkout cannot spend a use of a limited code.
        applyDiscount(order, request.discountCode(), lines.stream()
            .map(line -> snapshots.get(line.variantId()).toDiscountLine(line.quantity()))
            .toList());

        order.setTotal(order.getSubtotal() - order.getDiscountAmount() + order.getDeliveryFee());
        order.touch();
        orderRepository.save(order);

        // Last, and remote. Everything above can still be rolled back without a trace.
        catalogGateway.reserveStock(order.getId(), items.stream()
            .map(item -> new CatalogGateway.StockLine(item.getVariantId(), item.getQuantity()))
            .toList());

        log.info("Order {} placed ({}): {} item(s), {} Rial", order.getTraceCode(), source,
            items.size(), order.getTotal());

        return OrderResponse.of(order, items);
    }

    /**
     * The order this key already placed, or null if it is new.
     *
     * <p>The lock is taken before the lookup, not after: two tabs submitting at once both miss the
     * lookup otherwise, and the second is refused by the unique index — a 500 on exactly the retry
     * the key exists to make safe.
     */
    private OrderResponse replayOf(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        orderRepository.lockIdempotencyKey(idempotencyKey);

        return orderRepository.findByIdempotencyKey(idempotencyKey)
            .map(existing -> {
                log.info("Checkout replayed with key {}; returning order {}",
                    idempotencyKey, existing.getTraceCode());
                return OrderResponse.of(existing,
                    orderItemRepository.findByOrderIdOrderByIdAsc(existing.getId()));
            })
            .orElse(null);
    }

    /** What was asked for, in the only terms both paths share. */
    public record RequestedLine(Long variantId, Integer quantity) {
    }

    // --- reads ---------------------------------------------------------------------------------

    /**
     * Looks an order up by the code the customer quotes.
     *
     * <p>Anonymous by design: a guest has no account to sign into, and the trace code <em>is</em>
     * the credential — which is why it is ten random characters rather than the primary key.
     */
    @Transactional(readOnly = true)
    public OrderResponse byTraceCode(String traceCode) {
        Order order = orderRepository.findByTraceCode(TraceCodes.normalise(traceCode))
            .orElseThrow(() -> ResourceNotFoundException.of("Order", traceCode));

        return OrderResponse.of(order, orderItemRepository.findByOrderIdOrderByIdAsc(order.getId()));
    }

    /** A signed-in shopper's order history, newest first. */
    @Transactional(readOnly = true)
    public List<OrderResponse> forUser(long userId) {
        List<Order> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(
            userId, org.springframework.data.domain.PageRequest.of(0, 50)).getContent();

        // One query for every order's items rather than one per order.
        Map<Long, List<OrderItem>> byOrder = orderItemRepository
            .findByOrderIdInOrderByIdAsc(orders.stream().map(Order::getId).toList())
            .stream()
            .collect(java.util.stream.Collectors.groupingBy(OrderItem::getOrderId));

        return orders.stream()
            .map(order -> OrderResponse.of(order, byOrder.getOrDefault(order.getId(), List.of())))
            .toList();
    }

    // --- building ------------------------------------------------------------------------------

    private Order newOrder(Long userId, CheckoutRequest request, DeliveryMethod delivery,
                           long subtotal, String idempotencyKey, OrderSource source) {
        Order order = new Order();
        order.setTraceCode(uniqueTraceCode());
        order.setUserId(userId);

        order.setBuyerFirstName(request.buyerFirstName().trim());
        order.setBuyerLastName(request.buyerLastName().trim());
        order.setBuyerPhone(IranianPhoneNumber.tryNormalize(request.buyerPhone())
            .orElseThrow(() -> new BusinessRuleException("invalid-phone",
                "That does not look like an Iranian mobile number.")));

        // A map rather than a typed address entity: this is a snapshot for display and for the
        // courier, and it must keep rendering unchanged if the address model ever grows a field.
        Map<String, String> address = new LinkedHashMap<>();
        address.put("province", request.province().trim());
        address.put("city", request.city().trim());
        address.put("addressLine", request.addressLine().trim());
        address.put("postalCode", request.postalCode().trim());
        order.setAddressSnapshot(address);
        order.setPostalCode(request.postalCode().trim());

        order.setDeliveryMethodId(delivery.getId());
        order.setDeliveryName(delivery.getName());
        order.setDeliveryFee(delivery.getFee());

        order.setSubtotal(subtotal);
        order.setDiscountAmount(0L);
        // Provisional, and never observed: the discount is applied and the total corrected before
        // this transaction commits. The column is NOT NULL, so it needs a value to be flushed at
        // all, and flushing is what yields the id the redemption and the reservation both need.
        order.setTotal(subtotal + delivery.getFee());

        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setDerivedStatus(FulfillmentStatus.PENDING);
        order.setSource(source);
        order.setIdempotencyKey(idempotencyKey);

        order.setCreatedAt(OffsetDateTime.now());
        order.setUpdatedAt(order.getCreatedAt());
        return order;
    }

    private OrderItem toOrderItem(Long orderId, RequestedLine line, VariantSnapshot snapshot) {
        Map<String, String> variant = new LinkedHashMap<>();
        if (snapshot.colorName() != null) {
            variant.put("color", snapshot.colorName());
        }
        if (snapshot.sizeName() != null) {
            variant.put("size", snapshot.sizeName());
        }
        if (snapshot.productSlug() != null) {
            variant.put("slug", snapshot.productSlug());
        }

        return OrderItem.of(orderId, snapshot.productId(), snapshot.variantId(),
            snapshot.sellerId(), snapshot.productName(), variant,
            snapshot.unitPrice(), line.quantity());
    }

    private void applyDiscount(Order order, String code,
                               List<com.aura.order.discount.DiscountLine> discountLines) {
        if (code == null || code.isBlank()) {
            return;
        }
        DiscountService.Redemption redemption =
            discountService.redeem(code, discountLines, order.getUserId(), order.getId());

        order.setDiscountAmount(redemption.amount());
        order.setDiscountCode(redemption.code());
        order.setDiscountCodeId(redemption.discountCodeId());
    }

    /**
     * Refuses the whole checkout if any line has become unbuyable.
     *
     * <p>All of them are named at once rather than one per attempt: a shopper made to discover
     * their problems one submission at a time will abandon the basket before the third.
     */
    private void requireAllPurchasable(List<RequestedLine> lines,
                                       Map<Long, VariantSnapshot> snapshots) {
        List<String> problems = new ArrayList<>();

        for (RequestedLine line : lines) {
            VariantSnapshot snapshot = snapshots.get(line.variantId());

            if (snapshot == null || !snapshot.purchasable()) {
                problems.add((snapshot == null ? "An item" : snapshot.productName())
                    + " is no longer sold.");
            } else if (snapshot.available() < line.quantity()) {
                problems.add(snapshot.productName() + ": only " + Math.max(snapshot.available(), 0)
                    + " left in stock.");
            }
        }

        if (!problems.isEmpty()) {
            throw new BusinessRuleException("cart-not-purchasable",
                "Your basket has changed. " + String.join(" ", problems));
        }
    }

    /**
     * Refuses a composed order containing anyone else's products.
     *
     * <p>The seller id is taken from catalog's snapshot rather than from the request, so a seller
     * cannot claim a line by asserting ownership of it.
     */
    private void requireAllBelongTo(long sellerId, List<RequestedLine> lines,
                                    Map<Long, VariantSnapshot> snapshots) {
        boolean foreign = lines.stream()
            .map(line -> snapshots.get(line.variantId()))
            .anyMatch(snapshot -> !snapshot.sellerId().equals(sellerId));

        if (foreign) {
            throw new BusinessRuleException("not-your-product",
                "An order link may only contain your own products.");
        }
    }

    private String uniqueTraceCode() {
        for (int attempt = 0; attempt < TRACE_CODE_ATTEMPTS; attempt++) {
            String candidate = TraceCodes.generate();
            if (!orderRepository.existsByTraceCode(candidate)) {
                return candidate;
            }
            log.warn("Trace code collision on attempt {} — improbable enough to be worth noticing",
                attempt + 1);
        }
        throw new IllegalStateException("Could not generate a unique trace code");
    }

    private java.util.Optional<Cart> findCart(CartOwner owner) {
        return owner.isUser()
            ? cartRepository.findByUserId(owner.userId())
            : cartRepository.findByCartToken(owner.cartToken());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
